package com.music.bitchord

import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an addon's `allowDownloads: 0` does — and does not — decide.
 *
 * The manifest switch marks an addon streaming-only for clients that honour
 * it. nextrobytemusic records it, and an explicit download still saves that
 * addon's stream: the download walk is the ordinary source walk, so a track
 * queued from a streaming-only addon is filed from that addon rather than
 * quietly handed to whoever is next in line.
 *
 * Worth its own test because the rule is expressed as an absence — nothing
 * filters on the flag — and an absence is exactly what a later "let's honour
 * it properly" change would restore without noticing it was deliberate.
 */
class DownloadSourcePolicyTest {

    private val before = SourceRegistry.configs.value

    @After
    fun tearDown() {
        SourceRegistry.configs.value = before
    }

    private fun addon(name: String, allowDownloads: Boolean) = SourceConfig(
        kind = SourceKind.ADDON,
        label = name,
        baseUrl = "https://$name.example.com",
        allowDownloads = allowDownloads,
    )

    /**
     * The switch is stored, because the manifest's answer is worth being able
     * to read back — but the walk ignores it.
     */
    @Test
    fun `a streaming-only addon is still a download source`() {
        val streamingOnly = addon("streaming-only", allowDownloads = false)
        SourceRegistry.add(streamingOnly)

        val downloadIds = SourceRegistry.activeForDownload().map { it.configId }
        assertTrue("the addon was skipped by the download walk", streamingOnly.id in downloadIds)
        assertEquals(
            "downloads and playback disagree about which sources exist",
            SourceRegistry.active().map { it.configId },
            downloadIds,
        )
    }

    /** The next source in line is not asked first just because this one said no. */
    @Test
    fun `a streaming-only addon keeps its place in the download order`() {
        val first = addon("first", allowDownloads = false)
        val second = addon("second", allowDownloads = true)
        SourceRegistry.add(first)
        SourceRegistry.add(second)

        assertEquals(
            listOf(first.id, second.id),
            SourceRegistry.activeForDownload().map { it.configId },
        )
    }

    /** The flag is still recorded, and still readable from the stored config. */
    @Test
    fun `the manifest answer is kept on the config`() {
        val streamingOnly = addon("streaming-only", allowDownloads = false)
        SourceRegistry.add(streamingOnly)

        val stored = SourceRegistry.config(streamingOnly.id)
        assertNotNull(stored)
        assertEquals(false, stored?.allowDownloads)
    }
}
