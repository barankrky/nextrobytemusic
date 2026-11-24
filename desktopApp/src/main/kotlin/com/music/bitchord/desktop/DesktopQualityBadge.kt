package com.music.bitchord.desktop

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.QualityBadge
import com.music.bitchord.data.QualityBadgeState
import com.music.bitchord.data.settings.AudioQuality

/**
 * The badge inputs the desktop player itself sees, from one playback snapshot.
 *
 * The composable under the seek bar reads [NerdStats.current], which the window
 * fills from [DesktopStreamFormat]; [NerdStats.racingLossless], which the
 * window fills from [DesktopPlaybackState.searchingBetter]; the window's
 * buffering flag; and the shared player settings' quality flows. This rebuilds
 * that exact set, so the Discord card and the badge can only ever ask the same
 * [QualityBadgeState] which label applies.
 *
 * The shared quality flows are the player's own inputs, not the desktop's
 * download/stream rung: [DesktopPlayerSettings] pins both to
 * [AudioQuality.LOSSLESS], which is why the desktop player never draws the
 * Data-Saver or Medium labels either. Feeding the card a different rung here
 * would let the two disagree.
 */
internal fun desktopQualityBadgeState(state: DesktopPlaybackState): QualityBadgeState {
    val metered = DesktopPlayerSettings.meteredConnection.value
    val effectiveQuality = if (metered == true) {
        DesktopPlayerSettings.audioQualityCellular.value
    } else {
        DesktopPlayerSettings.audioQualityWifi.value
    }
    return QualityBadgeState(
        isLoading = state.isLoading,
        // The same measured state [NerdStats.racingLossless] carries for this
        // song: the window publishes that set from this flag.
        stillRacing = state.searchingBetter,
        losslessRequested = effectiveQuality == AudioQuality.LOSSLESS,
        effectiveQuality = effectiveQuality,
        nerdStats = state.streamFormat?.toNerdStats(state.streamSourceId),
    )
}

/**
 * The decoder's measurement in the shape the player's badge and stats read.
 *
 * One function rather than the same construction in the window and the RPC:
 * the badge is only as honest as the snapshot it is handed, and two copies of
 * this mapping would eventually disagree about a codec or a field.
 */
internal fun DesktopStreamFormat.toNerdStats(sourceName: String?): NerdStats.Snapshot {
    val codec = codec?.substringAfterLast('/')?.substringBefore(';')?.trim()?.lowercase()
    return NerdStats.Snapshot(
        mimeType = codec?.let { "audio/$it" },
        bitrateKbps = kbps,
        sampleRateHz = sampleRateHz,
        channels = channels,
        bitDepth = bitDepth,
        sourceName = sourceName,
    )
}

/**
 * The desktop wording for a badge, keyed exactly as the player's own
 * compose-resources catalogue keys it.
 *
 * The two upgrading entries are part of the player's vocabulary; the Discord
 * card never asks for them — it passes [QualityBadgeState.settledBadge], which
 * has already taken the in-flight states out. They stay here so the resolver
 * covers the whole enum and so the player's words remain the single reference
 * for every label.
 *
 * The texts are the same in both catalogues; `DesktopDiscordPresenceTest`
 * reads the player's catalogue out of the sharedUi jar and asserts it for every
 * label, so a translation that changes on one side without the other fails a
 * test rather than reaching a Discord card.
 */
internal fun qualityBadgeLabel(
    badge: QualityBadge,
    strings: (String, String) -> String = DesktopStrings::get,
): String = when (badge) {
    QualityBadge.UPGRADING_HI_QUALITY ->
        strings("high_quality_upgrading", "High quality, upgrading")
    QualityBadge.UPGRADING ->
        strings("upgrading_quality", "Upgrading quality")
    QualityBadge.HI_RES_LOSSLESS ->
        strings("hi_res_lossless", "Hi-Res Lossless")
    QualityBadge.LOSSLESS ->
        strings("lossless", "Lossless")
    QualityBadge.DOLBY_ATMOS ->
        strings("dolby_atmos", "Dolby Atmos")
    QualityBadge.HIGH_QUALITY ->
        strings("high_quality", "High quality")
    QualityBadge.DATA_SAVER ->
        strings("data_saver", "Data-Saver")
    QualityBadge.MEDIUM_QUALITY ->
        strings("medium_quality", "Medium Quality")
}
