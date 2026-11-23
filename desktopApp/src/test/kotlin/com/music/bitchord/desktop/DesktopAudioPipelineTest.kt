package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The track-info facts the desktop pipeline readout draws. */
class DesktopAudioPipelineTest {

    @Test
    fun `a cached gateway file keeps its source and upstream`() {
        val pipeline = DesktopAudioPipeline.of(
            DesktopStream(
                url = "/tmp/track.flac",
                sourceId = "gateway",
                provider = "arcod",
                cached = true,
            ),
        )

        assertEquals("gateway", pipeline.sourceId)
        assertEquals("arcod", pipeline.provider)
        assertEquals(true, pipeline.cached)
        assertTrue(!pipeline.local)
    }

    @Test
    fun `a streaming source is streaming rather than cached`() {
        val pipeline = DesktopAudioPipeline.of(
            DesktopStream(url = "https://cdn/a.flac", sourceId = "gateway", provider = "monochrome"),
        )

        assertEquals("monochrome", pipeline.provider)
        assertEquals(false, pipeline.cached)
        assertTrue(!pipeline.local)
    }

    @Test
    fun `the listener's own file is local and has no provider`() {
        val pipeline = DesktopAudioPipeline.of(
            DesktopStream(url = "/home/me/Music/own.flac", local = true),
        )

        assertNull(pipeline.sourceId)
        assertNull(pipeline.provider)
        assertEquals(false, pipeline.cached)
        assertTrue(pipeline.local)
    }
}
