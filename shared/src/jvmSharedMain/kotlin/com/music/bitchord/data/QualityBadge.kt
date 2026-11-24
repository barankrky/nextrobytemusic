package com.music.bitchord.data

import com.music.bitchord.data.settings.AudioQuality

/**
 * The one quality badge the player draws under the seek bar, decided once for
 * every surface that shows it.
 *
 * The phone's player and the desktop Discord card both read the same measured
 * state — the decoder's [NerdStats.Snapshot], what the connection asked for,
 * whether the player is buffering and whether a lossless copy is still being
 * raced for — and both ask this type which badge that state earns. Keeping the
 * decision here is what makes "Yüksek kalite" on the card mean exactly the
 * "Yüksek kalite" under the seek bar; a second classifier over the raw format
 * would drift the moment either side gained a rule.
 *
 * The precedence below is the player's, rule for rule:
 *
 * 1. The upgrading states. Either a lossless race is still running over a
 *    stream that is not already lossless, or the player is buffering a
 *    lossless request with nothing measured yet. Both are a statement of
 *    intent, not a result. Inside that state, a stream already at the top of
 *    lossy says so ("Yüksek kalite, yükseltiliyor") rather than the bare
 *    "Kalite yükseltiliyor", which would read as "this is not good yet" on a
 *    320 kbps track whose lossless copy may not exist at all.
 * 2. Lossless, split into hi-res and CD-quality at the same line Tidal, Qobuz
 *    and Apple Music draw: past 16-bit or past 48 kHz is hi-res, not merely
 *    lossless.
 * 3. Dolby Atmos, which is its own measured kind and never also lossless.
 * 4. The top of lossy, at [NerdStats.HI_QUALITY_KBPS] and above.
 * 5. Data-Saver and Medium, but only while the connection's own quality rung
 *    asks for that tier — the same conditions the player applies. A low-bitrate
 *    stream that arrived while the listener asked for lossless is not called
 *    Data-Saver, because that would describe the setting rather than the track.
 *
 * Anything else earns nothing: the player draws no badge, and so must every
 * other surface.
 */
data class QualityBadgeState(
    val isLoading: Boolean,
    val stillRacing: Boolean,
    val losslessRequested: Boolean,
    val effectiveQuality: AudioQuality,
    val nerdStats: NerdStats.Snapshot?,
) {
    /** Exactly the one label this state earns, or null for no badge at all. */
    val badge: QualityBadge?
        get() = when {
            (stillRacing && nerdStats?.isLossless != true) ||
                (isLoading && losslessRequested && nerdStats == null) ->
                if (nerdStats?.isHiQuality == true) {
                    QualityBadge.UPGRADING_HI_QUALITY
                } else {
                    QualityBadge.UPGRADING
                }

            nerdStats?.isLossless == true ->
                if (nerdStats.isHiRes) QualityBadge.HI_RES_LOSSLESS else QualityBadge.LOSSLESS

            nerdStats?.isDolbyAtmos == true -> QualityBadge.DOLBY_ATMOS
            nerdStats?.isHiQuality == true -> QualityBadge.HIGH_QUALITY

            effectiveQuality == AudioQuality.LOW && nerdStats?.isLowQuality == true ->
                QualityBadge.DATA_SAVER

            effectiveQuality == AudioQuality.MEDIUM && nerdStats?.isMediumQuality == true ->
                QualityBadge.MEDIUM_QUALITY

            else -> null
        }

    /**
     * The same decision with the transient upgrade states taken out.
     *
     * The player asks for [badge] and may say "upgrading" while it looks for a
     * better copy. A surface that must not publish an in-between word — the
     * Discord card — asks for this instead: the measured stream's own label,
     * unchanged while the search runs, and the new format's label as soon as
     * it arrives. With nothing measured yet there is no settled label at all,
     * so the caller omits the quality segment rather than inventing one.
     */
    val settledBadge: QualityBadge?
        get() = copy(isLoading = false, stillRacing = false).badge
}

/** Every label the quality badge can take, in the player's precedence order. */
enum class QualityBadge {
    /** A lossless race is running over a stream already at the top of lossy. */
    UPGRADING_HI_QUALITY,

    /** A lossless fetch is in flight with nothing measured yet. */
    UPGRADING,

    /** Bit-exact, past CD: more than 16-bit or more than 48 kHz. */
    HI_RES_LOSSLESS,

    /** Bit-exact at CD resolution. */
    LOSSLESS,

    /** The immersive mix, measured as E-AC-3 JOC. */
    DOLBY_ATMOS,

    /** Lossy, but at the top of what lossy gets. */
    HIGH_QUALITY,

    /** Low rung, and the stream really is a low-bitrate one. */
    DATA_SAVER,

    /** Medium rung, and the stream really is in the medium band. */
    MEDIUM_QUALITY,
}
