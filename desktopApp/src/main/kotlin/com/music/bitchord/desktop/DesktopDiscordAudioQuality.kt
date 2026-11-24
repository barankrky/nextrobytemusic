package com.music.bitchord.desktop

import com.music.bitchord.data.NerdStats
import java.util.Locale

/**
 * The measured audio-quality wording on nextrobytemusic's Discord card.
 *
 * The card has one line under the artist — Discord draws `assets.large_text`
 * there, after `details` and `state` — so the wording is a single line: a
 * headphone glyph and the player's own localized quality badge, then the
 * technical fields the decoder measured, separated by the same middle dot the
 * rest of the card uses.
 *
 * Channel layout is deliberately left out here even though the player's own
 * format summary keeps it. The card names the four facts the listener asked
 * for — codec, bitrate, bit depth and sample rate — and a channel word would
 * add a fifth thing to read without adding a quality distinction.
 *
 * The figures are the decoder's measured ones from
 * [DesktopPlaybackState.streamFormat], never the quality a source merely
 * advertised: a lossy fallback must not continue calling itself Lossless on
 * Discord. The player already prefers the decoder's measurement over the
 * source's claim when it publishes the state, so reading that state is what
 * keeps the two honest.
 *
 * Android draws this line only for premium formats (`DiscordAudioQuality.kt`).
 * The desktop shows the measured encoding for ordinary lossy streams too —
 * "Opus · 160 kbps · 48 kHz" is exactly the kind of thing a local Discord card
 * is read for — and leaves the line absent until a decoder has measured
 * something, so a card never states a format it has not seen.
 */
internal data class DiscordQualityCard(
    /** "🎧 Hi-Res Kayıpsız", or null when the player's own badge has no label. */
    val badge: String?,
    /** "FLAC · 2304 kbps · 24-bit · 48 kHz", or null before anything was measured. */
    val specs: String?,
) {
    /** What `assets.large_text` carries: the badge and the fields as one sentence. */
    val line: String?
        get() = listOfNotNull(badge, specs)
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
            ?.take(DISCORD_TEXT_LIMIT)
            ?.trimEnd()
}

/**
 * Which of the player's badges applies, from what the decoder measured.
 *
 * The three lossless labels are the ones the player draws from
 * `NerdStats.Snapshot` (see `LosslessOrStats`), and the wording is resolved
 * through [DesktopStrings] so the card says "Hi-Res Kayıpsız" in Turkish
 * exactly as the badge under the seek bar does.
 *
 * Low, medium and Data-Saver are not inferred here. The player shows those
 * only when the connection's own quality setting asks for that tier, which
 * this value cannot see; calling a 64 kbps stream Data-Saver on the card while
 * the player shows nothing would be a guess, not a mirror. A measured lossy
 * stream that is not at the top of lossy still gets its codec and bitrate in
 * the line, just without a tier word.
 */
internal enum class DiscordQualityBadge(internal val key: String, private val english: String) {
    HI_RES_LOSSLESS("hi_res_lossless", "Hi-Res Lossless"),
    LOSSLESS("lossless", "Lossless"),
    DOLBY_ATMOS("dolby_atmos", "Dolby Atmos"),
    HIGH_QUALITY("high_quality", "High quality"),
    ;

    /** The same label, in the app's current language, as the player draws it. */
    val label: String
        get() = DesktopStrings[key, english]
}

/** The badge for [format], or null when the player would draw none. */
internal fun discordQualityBadge(format: DesktopStreamFormat?): DiscordQualityBadge? = when {
    format == null -> null
    // E-AC-3 JOC is its own measurement, never a bit-exact rendition. The
    // lossless check below cannot currently claim it, but checking Dolby first
    // keeps that true if the lossless codec set ever grows.
    format.isDolbyAtmos -> DiscordQualityBadge.DOLBY_ATMOS
    format.isHiRes -> DiscordQualityBadge.HI_RES_LOSSLESS
    format.isLossless -> DiscordQualityBadge.LOSSLESS
    format.isHiQuality -> DiscordQualityBadge.HIGH_QUALITY
    else -> null
}

/**
 * The card's quality wording as its two parts, or null when nothing measurable
 * is known. The badge mirrors the player's; the specs always name whichever of
 * codec, bitrate, bit depth and sample rate the decoder actually reported.
 *
 * [label] is the wording source. It defaults to the catalogue in force — the
 * card follows the app's language — and is a parameter so the wording can be
 * tested against the shipped catalogues without changing the language of
 * whoever runs the tests.
 */
internal fun discordQualityCard(
    format: DesktopStreamFormat?,
    label: (DiscordQualityBadge) -> String = { it.label },
): DiscordQualityCard? {
    format ?: return null

    val badge = discordQualityBadge(format)?.let { "🎧 ${label(it)}" }
    val specs = buildList {
        format.codecLabel?.let(::add)
        format.statedKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
        format.bitDepth?.takeIf { it > 0 }?.let { add("$it-bit") }
        format.sampleRateHz?.takeIf { it > 0 }?.let { add(sampleRateLabel(it)) }
    }.joinToString(" · ").ifBlank { null }

    // No badge and no measured field: the decoder has not said anything worth
    // putting on the card yet, and an empty or invented line would be worse
    // than the album it replaces.
    if (badge == null && specs == null) return null

    return DiscordQualityCard(badge = badge, specs = specs)
}

/** The one line sent as `assets.large_text`, or null when there is nothing to say. */
internal fun discordAudioQualityLine(
    format: DesktopStreamFormat?,
    label: (DiscordQualityBadge) -> String = { it.label },
): String? = discordQualityCard(format, label)?.line

/**
 * The card's third information line: the measured quality while its switch is
 * on and the decoder has something to say, the album otherwise.
 */
internal fun discordThirdLine(
    format: DesktopStreamFormat?,
    showQuality: Boolean,
    album: String?,
    label: (DiscordQualityBadge) -> String = { it.label },
): String? = discordAudioQualityLine(format, label).takeIf { showQuality } ?: album

/** DSD rates are named in MHz; everything else reads better in kHz. */
private fun sampleRateLabel(hz: Int): String =
    NerdStats.megahertzLabel(hz)
        ?: (String.format(Locale.ROOT, "%.1f", hz / 1000f).removeSuffix(".0") + " kHz")

/** Discord caps each activity text field at 128 characters. */
private const val DISCORD_TEXT_LIMIT = 128
