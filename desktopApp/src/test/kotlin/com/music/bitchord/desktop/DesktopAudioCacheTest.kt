package com.music.bitchord.desktop

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop audio cache's window map and budget.
 *
 * The store is driven directly against a temporary directory: the thing worth
 * holding is the bookkeeping — which windows are really on disk, when a file
 * counts as complete, and which entry the LRU takes — not a network fetch.
 */
class DesktopAudioCacheTest {

    private fun store(limit: Long = 1_024 * 1_024) =
        DesktopAudioCacheStore(
            directory = Files.createTempDirectory("nextro-audio-cache"),
            windowBytes = 4,
            limitBytes = { limit },
        ).also { it.directory.toFile().deleteOnExit() }

    @Test
    fun `a complete window map is a playable file`() {
        val store = store()
        val key = "addon:gateway:track-1|LOSSLESS"
        val entry = store.entryFor(key)

        entry.writeWindow(0, byteArrayOf(1, 2, 3, 4), totalLength = 8)
        assertFalse(entry.complete)
        entry.writeWindow(4, byteArrayOf(5, 6, 7, 8), totalLength = 8)

        assertTrue(entry.complete)
        val file = assertNotNull(store.completeFile(key))
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), Files.readAllBytes(file))
    }

    @Test
    fun `a partial map survives a restart and is not a complete file`() {
        val store = store()
        val key = "module:index:track-1|HIGH"
        store.entryFor(key).writeWindow(0, byteArrayOf(9, 8, 7, 6), totalLength = 8)

        val reopened = DesktopAudioCacheStore(store.directory, windowBytes = 4) { 1_024 * 1_024 }
        val entry = reopened.entryFor(key)

        assertFalse(entry.complete)
        assertNull(reopened.completeFile(key))
        assertContentEquals(byteArrayOf(9, 8, 7, 6), entry.readWindow(0))
        assertNull(entry.readWindow(4))
    }

    @Test
    fun `quality is part of the cache key`() {
        val store = store()
        val lossless = "addon:gateway:track-1|LOSSLESS"
        val high = "addon:gateway:track-1|HIGH"
        store.entryFor(lossless).writeWindow(0, byteArrayOf(1, 2, 3, 4), totalLength = 4)

        assertNotNull(store.completeFile(lossless))
        assertNull(store.completeFile(high))
    }

    /**
     * The cached-track check replaces a 16/44.1 file with a Hi-Res one without
     * the track or rung changing. They must not share an entry: the reader
     * serves a complete entry's windows straight from disk, so writing the new
     * file's windows over the old index would leave a mix of two renderings.
     */
    @Test
    fun `a better rendition becomes the one the next play opens`() {
        val store = store()
        val canonical = "addon:gateway:track-1|LOSSLESS|stereo"
        val cd = store.entryFor("$canonical|gateway|FLAC|44.1 kHz|/a.flac", canonicalKey = canonical)
        cd.writeWindow(0, byteArrayOf(1, 2, 3, 4), totalLength = 4)
        assertContentEquals(
            byteArrayOf(1, 2, 3, 4),
            Files.readAllBytes(assertNotNull(store.completeFile(canonical))),
        )

        val hiRes = store.entryFor("$canonical|gateway|FLAC|24-bit|96 kHz|/b.flac", canonicalKey = canonical)
        hiRes.writeWindow(0, byteArrayOf(9, 8, 7, 6), totalLength = 4)

        assertContentEquals(
            byteArrayOf(9, 8, 7, 6),
            Files.readAllBytes(assertNotNull(store.completeFile(canonical))),
        )
        // The replaced rendition is still its own file, untouched by the new one.
        assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(cd.data))
    }

    @Test
    fun `invalidating forgets the rendition the pointer chose`() {
        val store = store()
        val canonical = "addon:gateway:track-9|LOSSLESS|stereo"
        store.entryFor("$canonical|gateway|FLAC|44.1 kHz|/a.flac", canonicalKey = canonical)
            .writeWindow(0, byteArrayOf(1, 2, 3, 4), totalLength = 4)

        assertNotNull(store.completeFile(canonical))
        store.invalidate(canonical)
        assertNull(store.completeFile(canonical))
    }

    @Test
    fun `the coldest entry is evicted when the budget is crossed`() {
        val store = store(limit = 7)
        val first = store.entryFor("addon:a:1|LOSSLESS")
        val second = store.entryFor("addon:a:2|LOSSLESS")

        first.writeWindow(0, byteArrayOf(1, 2, 3, 4), totalLength = 4)
        Files.setLastModifiedTime(first.data, FileTime.fromMillis(1))
        second.writeWindow(0, byteArrayOf(5, 6, 7, 8), totalLength = 4)

        assertNull(store.completeFile("addon:a:1|LOSSLESS"), "the older entry should have gone")
        assertNotNull(store.completeFile("addon:a:2|LOSSLESS"))
        assertTrue(store.sizeBytes() <= 7)
    }

    @Test
    fun `provenance is persisted beside the rendition and follows the pointer`() {
        val store = store()
        val canonical = "addon:gateway:track-7|LOSSLESS|stereo"
        val rendition = "$canonical|gateway|FLAC|24-bit|96 kHz|/a.flac"
        val entry = store.entryFor(rendition, canonicalKey = canonical)
        entry.recordProvenance(DesktopCacheProvenance(sourceId = "gateway", provider = "arcod"))
        entry.writeWindow(0, byteArrayOf(1, 2, 3, 4), totalLength = 4)

        val reopened = DesktopAudioCacheStore(store.directory, windowBytes = 4) { 1_024 * 1_024 }
        val provenance = assertNotNull(reopened.provenance(canonical))
        assertEquals("gateway", provenance.sourceId)
        assertEquals("arcod", provenance.provider)
    }

    @Test
    fun `the pointer moves provenance with the rendition it chooses`() {
        val store = store()
        val canonical = "addon:gateway:track-8|LOSSLESS|stereo"
        val cd = store.entryFor("$canonical|gateway|FLAC|44.1 kHz|/a.flac", canonicalKey = canonical)
        cd.recordProvenance(DesktopCacheProvenance("gateway", "arcod"))
        cd.writeWindow(0, byteArrayOf(1, 2, 3, 4), totalLength = 4)

        val hiRes = store.entryFor("$canonical|gateway|FLAC|96 kHz|/b.flac", canonicalKey = canonical)
        hiRes.recordProvenance(DesktopCacheProvenance("gateway", "monochrome"))
        hiRes.writeWindow(0, byteArrayOf(9, 8, 7, 6), totalLength = 4)

        assertEquals("monochrome", store.provenance(canonical)?.provider)
    }

    @Test
    fun `a prefetch downloads the whole file into a playable entry`() = runBlocking {
        val body = ByteArray(90_000) { (it % 239).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/track") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val store = DesktopAudioCacheStore(
                directory = Files.createTempDirectory("nextro-audio-prefetch"),
                windowBytes = 32 * 1024,
                limitBytes = { 10L * 1024 * 1024 },
            )
            val key = "addon:gateway:track-3|LOSSLESS"
            store.prefetch(key, "http://127.0.0.1:${server.address.port}/track", emptyMap())

            val file = assertNotNull(store.completeFile(key))
            assertContentEquals(body, Files.readAllBytes(file))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a range reader fills the cache and a second reader never asks the server`() {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requested = mutableListOf<String>()
        server.createContext("/media") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            requested += range ?: "(none)"
            val match = Regex("bytes=(\\d+)-(\\d+)").find(range.orEmpty())
            if (match == null) {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
                return@createContext
            }
            val from = match.groupValues[1].toInt()
            val to = minOf(match.groupValues[2].toInt(), body.size - 1)
            val slice = body.copyOfRange(from, to + 1)
            exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${body.size}")
            exchange.sendResponseHeaders(206, slice.size.toLong())
            exchange.responseBody.use { it.write(slice) }
        }
        server.start()
        try {
            val store = DesktopAudioCacheStore(
                directory = Files.createTempDirectory("nextro-audio-range"),
                windowBytes = 32 * 1024,
                limitBytes = { 10L * 1024 * 1024 },
            )
            val entry = store.entryFor("addon:gateway:track-2|LOSSLESS")
            val first = DesktopRangeStream(
                url = "http://127.0.0.1:${server.address.port}/media",
                windowBytes = 32 * 1024,
                cache = entry,
            )
            val collected = ArrayList<Byte>(body.size)
            val buffer = ByteArray(8192)
            while (true) {
                val taken = first.read(buffer, buffer.size)
                if (taken <= 0) break
                repeat(taken) { collected += buffer[it] }
            }
            assertContentEquals(body, collected.toByteArray())
            assertTrue(entry.complete)

            val before = requested.size
            val second = DesktopRangeStream(
                url = "http://127.0.0.1:1/should-not-be-asked",
                windowBytes = 32 * 1024,
                cache = entry,
            )
            val replayed = ArrayList<Byte>(body.size)
            while (true) {
                val taken = second.read(buffer, buffer.size)
                if (taken <= 0) break
                repeat(taken) { replayed += buffer[it] }
            }
            assertContentEquals(body, replayed.toByteArray())
            assertEquals(before, requested.size, "the cached replay went to the network")
        } finally {
            server.stop(0)
        }
    }
}
