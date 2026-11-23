package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the cached-track check decides that a source's copy is worth a seam in the audio.
 *
 * The check runs on every cached play, so the bar matters: a source that answers with the same
 * master under another row must not interrupt the track, while a genuine step up — a higher bit
 * depth or sample rate, or lossless over lossy — should.
 */
class DesktopCachedUpgradeTest {

    private val cd = DesktopStreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100)

    @Test
    fun `lossless beats lossy`() {
        assertTrue(DesktopSourceRegistry.worthSwapping(cd, DesktopStreamFormat(codec = "opus", kbps = 160)))
        assertTrue(DesktopSourceRegistry.worthSwapping(cd, null))
    }

    @Test
    fun `a strictly better lossless copy wins`() {
        assertTrue(
            DesktopSourceRegistry.worthSwapping(
                DesktopStreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 96_000),
                cd,
            ),
        )
        // More bit depth at the same rate is still a better master.
        assertTrue(
            DesktopSourceRegistry.worthSwapping(
                DesktopStreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 44_100),
                cd,
            ),
        )
    }

    @Test
    fun `an equal or lower lossless copy is not an upgrade`() {
        assertFalse(
            DesktopSourceRegistry.worthSwapping(
                DesktopStreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 44_100),
                cd,
            ),
        )
        assertFalse(
            DesktopSourceRegistry.worthSwapping(
                DesktopStreamFormat(codec = "flac", bitDepth = 16, sampleRateHz = 48_000),
                DesktopStreamFormat(codec = "flac", bitDepth = 24, sampleRateHz = 96_000),
            ),
        )
        // Unknown fields take the CD defaults, so an undeclared copy cannot win on a guess.
        assertFalse(DesktopSourceRegistry.worthSwapping(DesktopStreamFormat(codec = "flac"), cd))
    }

    @Test
    fun `a better lossy copy still needs a wide gain`() {
        assertTrue(
            DesktopSourceRegistry.worthSwapping(
                DesktopStreamFormat(codec = "aac", kbps = 320),
                DesktopStreamFormat(codec = "opus", kbps = 160),
            ),
        )
        assertFalse(
            DesktopSourceRegistry.worthSwapping(
                DesktopStreamFormat(codec = "mp3", kbps = 192),
                DesktopStreamFormat(codec = "aac", kbps = 128),
            ),
        )
        // Never away from a lossless copy to a lossy one.
        assertFalse(DesktopSourceRegistry.worthSwapping(DesktopStreamFormat(codec = "aac", kbps = 320), cd))
    }
}
