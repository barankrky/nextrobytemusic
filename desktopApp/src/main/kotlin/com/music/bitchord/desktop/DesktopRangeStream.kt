package com.music.bitchord.desktop

import com.music.bitchord.data.innertube.StreamResolver
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Reads a remote file in bounded windows rather than as one open-ended request. */
internal class DesktopRangeStream(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val windowBytes: Int = DEFAULT_WINDOW,
    /**
     * The on-disk window map this reader fills as it goes, when the stream is
     * one [DesktopAudioCache] keeps. A window that is already there costs no
     * request; a missing one is fetched and written in the same pass.
     */
    private val cache: DesktopAudioCacheStore.Entry? = null,
) {

    init {
        require(cache == null || cache.windowBytes == windowBytes) {
            "the cache window is ${cache?.windowBytes} bytes, not $windowBytes"
        }
    }

    /** Total length in bytes, or -1 until the first response has stated one. */
    var length: Long = -1L
        private set

    private var position = 0L
    private var window = ByteArray(0)

    /** Where [window] starts in the file; -1 when nothing is held. */
    private var windowStart = -1L

    /** Where the next read will come from. */
    fun position(): Long = position

    fun seek(offset: Long) {
        position = offset.coerceAtLeast(0)
    }

    /**
     * Fills up to [count] bytes at the current position, returning how many — or -1 at the end of
     * the file.
     */
    fun read(into: ByteArray, count: Int): Int {
        if (count <= 0) return 0
        if (length >= 0 && position >= length) return -1
        if (!holds(position) && !fetchWindowFor(position)) return -1
        val offset = (position - windowStart).toInt()
        val available = window.size - offset
        if (available <= 0) return -1
        val taken = minOf(count, available)
        System.arraycopy(window, offset, into, 0, taken)
        position += taken
        return taken
    }

    private fun holds(at: Long): Boolean =
        windowStart >= 0 && at >= windowStart && at < windowStart + window.size

    /** Fetches the window containing [at], or serves it from the cache. */
    private fun fetchWindowFor(at: Long): Boolean {
        val start = at - (at % windowBytes)
        runCatching { cache?.readWindow(start) }.getOrNull()?.let { cached ->
            windowStart = start
            window = cached
            cache?.length?.let { length = it }
            return cached.isNotEmpty()
        }
        val end = start + windowBytes - 1

        fun send(withRange: Boolean): HttpResponse<ByteArray>? {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "*/*")
                // Anything else would arrive compressed and the byte offsets the caller is counting
                // in would stop meaning anything.
                .header("Accept-Encoding", "identity")
            if (withRange) builder.header("Range", "bytes=$start-$end")
            headers.forEach { (name, value) ->
                if (withRange || !name.equals("Range", ignoreCase = true)) {
                    runCatching { builder.header(name, value) }
                }
            }
            return runCatching {
                client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            }.getOrNull()
        }

        val ranged = send(withRange = true) ?: return false
        // An ordinary static file answers a Range with 206 and an addon that does not know the
        // header may answer 200 with the whole body; both are fine. A 403 or 416 on the range is
        // the one case worth a second request without it, because forcing windowed reads for the
        // cache must not turn a playable addon stream into a failed one.
        val response = if (ranged.statusCode() == 403 || ranged.statusCode() == 416) {
            send(withRange = false) ?: ranged
        } else {
            ranged
        }
        if (response.statusCode() !in 200..299) {
            // A URL cleared before playback has been refused mid-track: tell the resolver, as the
            // phone's ChunkedDataSource does, so it forgets it and benches the client that minted it.
            StreamResolver.onPlaybackRefused(url, response.statusCode())
            return false
        }

        val body = response.body()
        // A server that honoured the range says where the bytes came from and how many there are in
        // total.
        val stated = response.headers().firstValue("content-range").orElse(null)
        if (stated != null) {
            windowStart = stated.substringAfter("bytes ").substringBefore('-').trim().toLongOrNull() ?: start
            length = stated.substringAfterLast('/').trim().toLongOrNull() ?: length
        } else {
            windowStart = 0
            length = body.size.toLong()
        }
        window = body
        if (length < 0) length = windowStart + body.size
        cache?.let { entry ->
            // A server is allowed to answer a range request with a different span. Only a span that
            // starts on our window grid can be written into the map; anything else is left uncached
            // rather than made to look like the window at this offset.
            if (windowStart % windowBytes == 0L) {
                runCatching {
                    var offset = windowStart
                    var at = 0
                    while (at < body.size) {
                        val size = minOf(windowBytes, body.size - at)
                        entry.writeWindow(
                            start = offset,
                            body = body.copyOfRange(at, at + size),
                            totalLength = length.takeIf { it > 0 },
                            trimAfter = false,
                        )
                        at += size
                        offset += size
                    }
                    entry.trim()
                }
            }
        }
        return body.isNotEmpty()
    }

    private companion object {
        /** Comfortably inside what Google will serve. */
        const val DEFAULT_WINDOW = 512 * 1024

        val client: HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }
}
