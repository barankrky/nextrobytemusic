package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name

/**
 * The on-disk copy of addon and module audio.
 *
 * Desktop used to hand every addon stream straight to FFmpeg, so a track
 * played twice was fetched twice: once for each listen, and once more for
 * every seek that left the decoder's window. Android has had an audio cache
 * for exactly this reason for a long time; this is the desktop half.
 *
 * The cache is a window map, not a whole-file-only store. A playback reader
 * asks for the window it needs; a cached window is served from disk, a missing
 * one is fetched and written in the same pass. That means the first play fills
 * the cache without a second download, a seek only pays for the windows it
 * lands on, and a track stopped halfway resumes from its cached prefix next
 * time. A complete entry is played as an ordinary local file and never touches
 * the network.
 *
 * Only progressive addon/module streams are stored. An HLS or DASH manifest is
 * an index of segments, not audio; caching its bytes would keep a few KB of
 * XML instead of the track. Manifests keep streaming exactly as before.
 */
internal object DesktopAudioCache {

    /** The window granularity of a cache entry; [DesktopRangeStream] must use the same size. */
    const val WINDOW_BYTES = 512 * 1024

    private val store = DesktopAudioCacheStore(
        directory = defaultDirectory(),
        windowBytes = WINDOW_BYTES,
        limitBytes = { limitBytes() },
    )

    private fun defaultDirectory(): Path {
        val root = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
            ?: "${System.getProperty("user.home")}/.cache"
        return Path.of(root, "nextrobytemusic", "audio").also { runCatching { Files.createDirectories(it) } }
    }

    private fun key(song: Song, quality: String): String {
        // Atmos is a different requested rendition, not a footnote on the same one: a file cached
        // while the setting was on must not be served after it is turned off (and vice versa).
        val atmos = if (DesktopAddonSettings.dolbyAtmosEnabled) "atmos" else "stereo"
        return "${song.videoId}|${quality.uppercase(Locale.ROOT)}|$atmos"
    }

    /** The cache entry for [song] at [quality], if this stream is one worth keeping. */
    fun entryFor(song: Song, quality: String, stream: DesktopStream): DesktopAudioCacheStore.Entry? {
        if (!cacheable(stream)) return null
        // The cache is an optimisation: a full disk or an unreadable cache directory must leave
        // playback streaming as it did before, not fail the track that was about to play.
        return runCatching { store.entryFor(key(song, quality)).also { it.acquire() } }
            .onFailure { DesktopTrackLog.log("audio cache unavailable: ${it.message}") }
            .getOrNull()
    }

    /** A complete local copy of [song] at [quality], or null when there is not one. */
    fun completeFile(song: Song, quality: String): Path? =
        runCatching { store.completeFile(key(song, quality)) }.getOrNull()

    /** Downloads the whole track into the cache in the background, if it is not there already. */
    suspend fun prefetch(song: Song, quality: String, stream: DesktopStream) {
        if (!cacheable(stream)) return
        store.prefetch(key(song, quality), stream.url, stream.headers)
    }

    /** Forgets a cache entry that could not be decoded, so the next play fetches it again. */
    fun invalidate(song: Song, quality: String) {
        runCatching { store.invalidate(key(song, quality)) }
    }

    /** Whether [path] is one of this cache's data files, for a failure retry to invalidate. */
    fun owns(path: Path): Boolean =
        path.toAbsolutePath().normalize().startsWith(store.directory.toAbsolutePath().normalize())

    /** The configured source behind [song], for a cached stream's fallback path. */
    fun sourceIdFor(song: Song): String? =
        DesktopAddonSource.parseTrack(song.videoId)?.sourceId
            ?: DesktopModuleSource.parseTrack(song.videoId)?.sourceId

    /**
     * Whether [stream] is audio this cache can store.
     *
     * Addons and modules only: YouTube already has its own read-ahead and
     * JioSaavn's files are small enough that caching them is not the point of
     * this change. A manifest is refused above all — its bytes are not the
     * recording.
     */
    fun cacheable(stream: DesktopStream): Boolean {
        if (stream.transport != null) return false
        val url = stream.url.lowercase()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        // An addon may hand back a manifest URL without declaring its transport. Its bytes are an
        // index, not the recording, so the extension is the last chance to notice.
        val path = url.substringBefore('?')
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return false
        val sourceId = stream.sourceId ?: return false
        if (sourceId == "youtube" || sourceId == "jiosaavn") return false
        return when (DesktopSourceRegistry.kindOf(sourceId)) {
            DesktopSourceKind.ADDON,
            DesktopSourceKind.NEXTRO_HIGH_RES,
            DesktopSourceKind.MODULE,
            DesktopSourceKind.CUSTOM_MODULE,
            -> true
            else -> false
        }
    }

    fun limitMb(): Int =
        DesktopPersistence().int(KEY_CACHE_LIMIT_MB, DEFAULT_LIMIT_MB).coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)

    fun setLimitMb(megabytes: Int) {
        DesktopPersistence().saveInt(KEY_CACHE_LIMIT_MB, megabytes.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB))
        runCatching { store.trim() }
    }

    fun limitBytes(): Long = limitMb().toLong() * 1024 * 1024

    fun sizeBytes(): Long = runCatching { store.sizeBytes() }.getOrDefault(0L)

    fun clear(): Long = runCatching { store.clear() }.getOrDefault(0L)

    /**
     * The same setting the Storage row used to call the song cache limit.
     * Keeping the key is deliberate: anyone who already chose a size for
     * "downloaded audio" should not silently get the default back when this
     * feature becomes real.
     */
    internal const val KEY_CACHE_LIMIT_MB = "media_cache_limit_mb"
    internal const val DEFAULT_LIMIT_MB = 512
    internal const val MIN_LIMIT_MB = 128
    internal const val MAX_LIMIT_MB = 10 * 1024

    /** Past this the Storage row warns, because it is a noticeable share of a disk. */
    internal const val WARNING_MB = 2048
}

/**
 * The window-addressed store behind [DesktopAudioCache], kept separate so the
 * bookkeeping can be exercised against a temporary directory.
 */
internal class DesktopAudioCacheStore(
    val directory: Path,
    val windowBytes: Int = DesktopAudioCache.WINDOW_BYTES,
    private val limitBytes: () -> Long = { Long.MAX_VALUE },
) {

    init {
        Files.createDirectories(directory)
    }

    /** One track's data and the windows of it that are known to be on disk. */
    inner class Entry internal constructor(
        val key: String,
        private val base: String,
    ) {
        val data: Path = directory.resolve("$base.audio")
        val windowBytes: Int get() = this@DesktopAudioCacheStore.windowBytes
        private val index: Path = directory.resolve("$base.idx")
        private val lengthFile: Path = directory.resolve("$base.len")
        private val done: Path = directory.resolve("$base.done")
        private val lock = Any()
        private val windows = HashSet<Long>()
        private var holders = 0

        @Volatile
        var length: Long? = null
            private set

        @Volatile
        var complete: Boolean = false
            private set

        init {
            length = readLong(lengthFile)
            runCatching { Files.readAllLines(index) }.getOrNull().orEmpty().forEach { line ->
                line.trim().toLongOrNull()?.let { windows += it }
            }
            complete = Files.isRegularFile(data) && Files.exists(done) && hasAllWindows()
        }

        fun acquire() {
            synchronized(lock) { holders++ }
        }

        fun release() {
            synchronized(lock) { holders = (holders - 1).coerceAtLeast(0) }
        }

        internal fun active(): Boolean = synchronized(lock) { holders > 0 }

        internal fun trim() = this@DesktopAudioCacheStore.trim()

        /** The bytes of the window starting at [start], or null when that window is not held. */
        fun readWindow(start: Long): ByteArray? = synchronized(lock) {
            val total = length
            if (total != null && start >= total) return null
            if (!complete && start / windowBytes !in windows) return null
            if (!Files.isRegularFile(data)) return null
            val expected = expectedWindowSize(start, total)
            if (expected <= 0) return null
            val out = ByteArray(expected)
            val read = Files.newByteChannel(data).use { channel ->
                channel.position(start)
                val buffer = ByteBuffer.wrap(out)
                var taken = 0
                while (buffer.hasRemaining()) {
                    val count = channel.read(buffer)
                    if (count <= 0) break
                    taken += count
                }
                taken
            }
            if (read <= 0) return null
            touch()
            if (read == out.size) out else out.copyOf(read)
        }

        /**
         * Writes one window and remembers it as complete.
         *
         * [totalLength] is the length the server stated, when it did. The
         * window is only marked as held when it is the whole window — a
         * truncated response is written but left out of the index, so the next
         * read asks for it again instead of serving a hole as audio.
         */
        fun writeWindow(start: Long, body: ByteArray, totalLength: Long?, trimAfter: Boolean = true) {
            if (body.isEmpty()) return
            require(start >= 0 && start % windowBytes == 0L) { "unaligned cache window at $start" }
            require(body.size <= windowBytes) { "a window may not exceed $windowBytes bytes" }
            synchronized(lock) {
                if (totalLength != null && totalLength > 0) {
                    length = totalLength
                    writeLong(lengthFile, totalLength)
                } else {
                    val end = start + body.size
                    val current = length
                    if (current == null || end > current) {
                        length = end
                        writeLong(lengthFile, end)
                    }
                }
                val channel = FileChannel.open(data, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                channel.use { opened ->
                    opened.position(start)
                    val buffer = ByteBuffer.wrap(body)
                    while (buffer.hasRemaining()) {
                        if (opened.write(buffer) <= 0) break
                    }
                }
                val expected = expectedWindowSize(start, totalLength ?: length)
                val window = start / windowBytes
                if (expected > 0 && body.size >= expected) {
                    if (windows.add(window)) writeIndex()
                } else {
                    windows.remove(window)
                }
                touch()
                complete = hasAllWindows()
                if (complete) {
                    runCatching {
                        Files.writeString(
                            done,
                            "1",
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                        )
                    }
                }
            }
            if (trimAfter) trim()
        }

        /** Marks the entry complete once a whole-file fetch has reached [totalLength]. */
        fun markComplete(totalLength: Long) {
            synchronized(lock) {
                length = totalLength
                writeLong(lengthFile, totalLength)
                complete = hasAllWindows()
                if (complete) {
                    runCatching {
                        Files.writeString(
                            done,
                            "1",
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                        )
                    }
                }
                touch()
            }
            trim()
        }

        /** The local file when the whole track is here, or null. */
        fun playbackFile(): Path? = synchronized(lock) {
            if (!complete) return null
            val total = length ?: return null
            if (!Files.isRegularFile(data) || runCatching { Files.size(data) }.getOrDefault(0L) < total) {
                complete = false
                runCatching { Files.deleteIfExists(done) }
                return null
            }
            touch()
            data
        }

        internal fun deleteFiles(): Long {
            val bytes = runCatching { Files.size(data) }.getOrDefault(0L)
            listOf(data, index, lengthFile, done).forEach { runCatching { Files.deleteIfExists(it) } }
            return bytes
        }

        internal fun lastModified(): Long =
            runCatching { Files.getLastModifiedTime(data).toMillis() }.getOrDefault(0L)

        private fun expectedWindowSize(start: Long, total: Long?): Int {
            val remaining = if (total != null) total - start else windowBytes.toLong()
            if (remaining <= 0) return 0
            return minOf(windowBytes.toLong(), remaining).toInt()
        }

        private fun hasAllWindows(): Boolean {
            val total = length ?: return false
            if (total <= 0) return false
            val count = (total + windowBytes - 1) / windowBytes
            for (window in 0L until count) {
                if (window !in windows) return false
            }
            return true
        }

        private fun touch() {
            runCatching {
                if (Files.exists(data)) Files.setLastModifiedTime(data, FileTime.fromMillis(System.currentTimeMillis()))
            }
        }

        private fun writeIndex() {
            runCatching {
                Files.writeString(
                    index,
                    windows.sorted().joinToString("\n"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
        }

        private fun readLong(path: Path): Long? =
            runCatching { Files.readString(path).trim().toLongOrNull() }.getOrNull()

        private fun writeLong(path: Path, value: Long) {
            runCatching {
                Files.writeString(
                    path,
                    value.toString(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
        }
    }

    private val known = ConcurrentHashMap<String, Entry>()
    private val prefetching = ConcurrentHashMap.newKeySet<String>()

    /** The entry for a logical track key, created on first use. */
    fun entryFor(key: String): Entry {
        val base = digest(key)
        return known.computeIfAbsent(base) { Entry(key, base) }
    }

    /** A file found on disk, whose logical key is no longer in hand. */
    private fun entryForBase(base: String): Entry =
        known.computeIfAbsent(base) { Entry(base, base) }

    /** A complete file for [key], or null. */
    fun completeFile(key: String): Path? = entryFor(key).playbackFile()

    /**
     * Fetches the whole track into the cache in one pass.
     *
     * Sequential windows, not one open-ended response: a track stopped early
     * leaves an index that the next playback can resume from, and the write
     * path is the same one [DesktopRangeStream] uses while it plays.
     */
    suspend fun prefetch(key: String, url: String, headers: Map<String, String>) {
        val entry = entryFor(key)
        if (entry.playbackFile() != null) return
        if (!prefetching.add(key)) return
        entry.acquire()
        try {
            withContext(Dispatchers.IO) {
                val request = DesktopDownloadHttp.request(url, DesktopAddonClient.USER_AGENT, headers)
                DesktopDownloadHttp.client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "cache fetch failed: HTTP ${response.code}" }
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0 }
                    if (total != null && total > limitBytes()) return@use
                    body.byteStream().use { input ->
                        val buffer = ByteArray(windowBytes)
                        var offset = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            var filled = 0
                            while (filled < buffer.size) {
                                val read = input.read(buffer, filled, buffer.size - filled)
                                if (read < 0) break
                                filled += read
                            }
                            if (filled <= 0) break
                            entry.writeWindow(
                                start = offset,
                                body = if (filled == buffer.size) buffer else buffer.copyOf(filled),
                                totalLength = total,
                                trimAfter = false,
                            )
                            offset += filled
                            if (filled < buffer.size) break
                        }
                        if (total == null || offset >= total) entry.markComplete(offset)
                    }
                }
            }
            trim()
        } finally {
            entry.release()
            prefetching.remove(key)
        }
    }

    fun invalidate(key: String) {
        val entry = entryFor(key)
        entry.deleteFiles()
        known.remove(digest(key), entry)
    }

    /** Everything in the cache, in bytes. */
    fun sizeBytes(): Long = dataFiles().sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }

    /** Throws everything away, returning how many bytes it freed. */
    fun clear(): Long {
        val freed = sizeBytes()
        files().forEach { runCatching { Files.deleteIfExists(it) } }
        known.clear()
        return freed
    }

    /**
     * Drops the least recently used entries until the cache fits the limit.
     *
     * An entry a playback reader is inside is skipped: it is the track the
     * listener is hearing, and deleting it would turn the next window into a
     * network round trip mid-song. It will be evicted on a later pass if it is
     * still cold.
     */
    fun trim() {
        val limit = limitBytes()
        if (limit <= 0) return
        var total = sizeBytes()
        if (total <= limit) return
        val candidates = dataFiles()
            .mapNotNull { data -> baseOf(data)?.let(::entryForBase) }
            .filterNot(Entry::active)
            .sortedBy(Entry::lastModified)
        for (entry in candidates) {
            if (total <= limit) break
            total -= entry.deleteFiles()
        }
    }

    private fun dataFiles(): List<Path> = runCatching {
        Files.list(directory).use { stream ->
            stream.filter { it.name.endsWith(".audio") && Files.isRegularFile(it) }.toList()
        }
    }.getOrDefault(emptyList())

    private fun baseOf(data: Path): String? = data.name.removeSuffix(".audio").takeIf(String::isNotBlank)

    private fun files(): List<Path> = runCatching {
        Files.list(directory).use { stream -> stream.filter(Files::isRegularFile).toList() }
    }.getOrDefault(emptyList())

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)
}
