package com.music.bitchord.data.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The precedence rule behind the pipeline screen's Source / Provider / Status rows. */
class PipelineProvenanceTest {

    @Test
    fun `a complete cache entry is cached and keeps the source that filled it`() {
        val source = PipelineProvenance.resolve(
            mediaId = "src:addon-1::ar_123",
            local = false,
            downloaded = false,
            cacheSourceName = "nextro's High-Res Source",
            cacheProvider = "bridge",
            cacheComplete = true,
            addonTrackId = "ar_123",
        )

        assertEquals("nextro's High-Res Source", source.name)
        assertEquals("lossless", source.provider)
        assertEquals(PipelinePlaybackStatus.CACHED, source.status)
    }

    @Test
    fun `a downloaded file is cached even when no cache entry exists`() {
        val source = PipelineProvenance.resolve(
            mediaId = "gd_42",
            local = true,
            downloaded = true,
            downloadSourceName = "nextro's High-Res Source",
            downloadProvider = "netease",
        )

        assertEquals("nextro's High-Res Source", source.name)
        assertEquals("netease", source.provider)
        assertEquals(PipelinePlaybackStatus.CACHED, source.status)
    }

    @Test
    fun `an old download with a source key derives the provider from its track id`() {
        val source = PipelineProvenance.resolve(
            mediaId = "src:addon-1::td_abc",
            local = true,
            downloaded = true,
            sourceTrackName = "nextro's High-Res Source",
            addonTrackId = "td_abc",
        )

        assertEquals("nextro's High-Res Source", source.name)
        assertEquals("monochrome", source.provider)
        assertEquals(PipelinePlaybackStatus.CACHED, source.status)
    }

    @Test
    fun `a stream still on the network is streaming`() {
        val source = PipelineProvenance.resolve(
            mediaId = "video-id",
            local = false,
            downloaded = false,
            recordedSourceName = "YouTube",
            fallbackSourceName = "YouTube",
        )

        assertEquals("YouTube", source.name)
        assertNull(source.provider)
        assertEquals(PipelinePlaybackStatus.STREAMING, source.status)
    }

    @Test
    fun `the listener's own file is local and is not claimed by a source`() {
        val source = PipelineProvenance.resolve(
            mediaId = "content://media/123",
            local = true,
            downloaded = false,
            fallbackSourceName = "Unknown",
        )

        assertEquals(PipelineProvenance.LOCAL_LABEL, source.name)
        assertNull(source.provider)
        assertEquals(PipelinePlaybackStatus.LOCAL, source.status)
    }

    @Test
    fun `a cache entry outranks a stale queue source name`() {
        val source = PipelineProvenance.resolve(
            mediaId = "video-id",
            local = false,
            downloaded = false,
            cacheSourceName = "My FLAC addon",
            cacheProvider = "arcod",
            cacheComplete = false,
            sourceTrackName = "YouTube",
            recordedSourceName = "YouTube",
        )

        assertEquals("My FLAC addon", source.name)
        assertEquals("arcod", source.provider)
        assertEquals(PipelinePlaybackStatus.STREAMING, source.status)
    }
}
