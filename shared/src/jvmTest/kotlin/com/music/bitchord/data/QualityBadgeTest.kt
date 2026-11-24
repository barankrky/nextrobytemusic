package com.music.bitchord.data

import com.music.bitchord.data.settings.AudioQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The one quality-badge decision both the player and the desktop Discord card
 * read. The cases are the player's own precedence, including the two upgrading
 * states and the rung-gated lossy labels, so a change here is visible in the
 * badge on screen and on Discord at the same time.
 */
class QualityBadgeTest {

    private fun snapshot(
        mime: String? = null,
        kbps: Int? = null,
        rateHz: Int? = null,
        bits: Int? = null,
        channels: Int? = 2,
    ) = NerdStats.Snapshot(
        mimeType = mime,
        bitrateKbps = kbps,
        sampleRateHz = rateHz,
        channels = channels,
        bitDepth = bits,
    )

    private fun state(
        nerdStats: NerdStats.Snapshot? = null,
        isLoading: Boolean = false,
        stillRacing: Boolean = false,
        losslessRequested: Boolean = true,
        effectiveQuality: AudioQuality = AudioQuality.LOSSLESS,
    ) = QualityBadgeState(
        isLoading = isLoading,
        stillRacing = stillRacing,
        losslessRequested = losslessRequested,
        effectiveQuality = effectiveQuality,
        nerdStats = nerdStats,
    )

    private val opus160 = snapshot(mime = "audio/opus", kbps = 160, rateHz = 48_000)
    private val aac320 = snapshot(mime = "audio/aac", kbps = 320, rateHz = 44_100)
    private val aac160 = snapshot(mime = "audio/aac", kbps = 160, rateHz = 48_000)
    private val opus64 = snapshot(mime = "audio/opus", kbps = 64, rateHz = 48_000)
    private val flacHiRes = snapshot(mime = "audio/flac", rateHz = 96_000, bits = 24)
    private val flacCd = snapshot(mime = "audio/flac", rateHz = 44_100, bits = 16)
    private val atmos = snapshot(mime = "audio/eac3-joc", kbps = 768, rateHz = 48_000, channels = 6)

    @Test
    fun `a hi-res lossless stream is named hi-res`() {
        assertEquals(QualityBadge.HI_RES_LOSSLESS, state(flacHiRes).badge)
    }

    @Test
    fun `a cd-quality lossless stream is named lossless rather than hi-res`() {
        assertEquals(QualityBadge.LOSSLESS, state(flacCd).badge)
    }

    @Test
    fun `dolby atmos is its own badge and never lossless`() {
        assertEquals(QualityBadge.DOLBY_ATMOS, state(atmos).badge)
    }

    @Test
    fun `the top of lossy is high quality`() {
        assertEquals(QualityBadge.HIGH_QUALITY, state(aac320).badge)
    }

    @Test
    fun `data saver needs the low rung and a genuinely low stream`() {
        assertEquals(
            QualityBadge.DATA_SAVER,
            state(opus64, effectiveQuality = AudioQuality.LOW).badge,
        )
        // A low stream on a higher rung is not Data-Saver; the label describes
        // the rung, and the player does not draw it there either.
        assertNull(state(opus64, effectiveQuality = AudioQuality.HIGH).badge)
    }

    @Test
    fun `medium needs the medium rung and a stream in the medium band`() {
        assertEquals(
            QualityBadge.MEDIUM_QUALITY,
            state(aac160, effectiveQuality = AudioQuality.MEDIUM).badge,
        )
        assertNull(state(aac160, effectiveQuality = AudioQuality.LOW).badge)
    }

    @Test
    fun `high quality wins over the low rung for a top-of-lossy stream`() {
        // The player checks isHiQuality before the rung-gated labels.
        assertEquals(
            QualityBadge.HIGH_QUALITY,
            state(aac320, effectiveQuality = AudioQuality.LOW).badge,
        )
    }

    @Test
    fun `an ordinary lossy stream on the lossless rung earns no badge`() {
        // The player draws nothing for 160 kbps Opus when lossless was asked
        // for; the Discord card must not invent a label for it.
        assertNull(state(opus160).badge)
    }

    @Test
    fun `an unmeasured buffering lossless request says upgrading`() {
        assertEquals(
            QualityBadge.UPGRADING,
            state(nerdStats = null, isLoading = true).badge,
        )
    }

    @Test
    fun `a buffering request that is not asking for lossless says nothing`() {
        assertNull(state(nerdStats = null, isLoading = true, losslessRequested = false).badge)
    }

    @Test
    fun `a lossless race over the top of lossy says high quality upgrading`() {
        assertEquals(
            QualityBadge.UPGRADING_HI_QUALITY,
            state(aac320, stillRacing = true).badge,
        )
    }

    @Test
    fun `a lossless race over an ordinary lossy stream says upgrading`() {
        assertEquals(
            QualityBadge.UPGRADING,
            state(opus160, stillRacing = true).badge,
        )
    }

    @Test
    fun `a lossless race over a lossless stream leaves the confirmed badge alone`() {
        assertEquals(
            QualityBadge.HI_RES_LOSSLESS,
            state(flacHiRes, stillRacing = true).badge,
        )
    }

    @Test
    fun `a rebuffer after a measurement does not say upgrading`() {
        // Once there is a measured snapshot, buffering is just buffering; the
        // player keeps showing what was measured instead of going back to the
        // intent label.
        assertNull(state(opus160, isLoading = true).badge)
    }

    @Test
    fun `a settled surface sees the measured stream's label while a race is in flight`() {
        val racing = state(aac320, stillRacing = true)
        // The player still says "high quality, upgrading"; a settled surface
        // (the Discord card) asks for the stream's own label instead.
        assertEquals(QualityBadge.UPGRADING_HI_QUALITY, racing.badge)
        assertEquals(QualityBadge.HIGH_QUALITY, racing.settledBadge)

        // Nothing measured yet: there is no settled label to keep.
        val buffering = state(nerdStats = null, isLoading = true)
        assertEquals(QualityBadge.UPGRADING, buffering.badge)
        assertNull(buffering.settledBadge)
    }

    @Test
    fun `a state with no upgrade in flight settles to its own badge`() {
        val cases = listOf(
            flacHiRes to AudioQuality.LOSSLESS,
            flacCd to AudioQuality.LOSSLESS,
            atmos to AudioQuality.LOSSLESS,
            aac320 to AudioQuality.LOSSLESS,
            aac160 to AudioQuality.MEDIUM,
            opus64 to AudioQuality.LOW,
            opus160 to AudioQuality.LOSSLESS,
        )
        cases.forEach { (snapshot, rung) ->
            val value = state(snapshot, effectiveQuality = rung)
            assertEquals(value.badge, value.settledBadge)
        }
    }

    @Test
    fun `a settled lossless stream keeps its new label even if the race flag has not cleared`() {
        // The measured format can land before the search reports done; the
        // settled view must already read the new rendition's label.
        assertEquals(
            QualityBadge.HI_RES_LOSSLESS,
            state(flacHiRes, stillRacing = true).settledBadge,
        )
    }
}
