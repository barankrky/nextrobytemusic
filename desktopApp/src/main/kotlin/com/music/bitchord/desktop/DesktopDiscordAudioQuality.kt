package com.music.bitchord.desktop

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.QualityBadge
import com.music.bitchord.data.QualityBadgeState
import java.util.Locale

/**
 * The measured audio-quality wording on nextrobytemusic's Discord card.
 *
 * The card has one line under the artist — Discord draws `assets.large_text`
 * there, after `details` and `state` — so the wording is a single line: a
 * headphone glyph and the player's own quality badge, then the technical
 * fields the decoder measured, separated by the same middle dot the rest of the
 * card uses.
 *
 * The badge is not classified here. [QualityBadgeState.settledBadge] in
 * `:shared` takes the player's one decision and strips the transient upgrade
 * states out, so this file only resolves the settled label to the catalogue's
 * wording and joins it to the specs. The card never says "upgrading": on
 * Discord that sentence can outlive the race that earned it, and the measured
 * stream's own label is already true while the race runs.
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
    /** "🎧 Hi-Res Kayıpsız", or null when the measured stream earns no settled label. */
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
 * The card's quality wording as its two parts, or null when the measured
 * stream has no settled label and the decoder has measured no field.
 *
 * The badge comes from [quality]'s settled state and nowhere else: this
 * function does not look at the codec to decide a label, and it deliberately
 * never takes the transient upgrade badge. The specs always name whichever of
 * codec, bitrate, bit depth and sample rate [format] actually reported, so a
 * transient state still carries what is actually playing.
 *
 * [label] is the wording source. It defaults to the catalogue in force — the
 * card follows the app's language — and is a parameter so the wording can be
 * tested against the shipped catalogues without changing the language of
 * whoever runs the tests.
 */
internal fun discordQualityCard(
    format: DesktopStreamFormat?,
    quality: QualityBadgeState?,
    label: (QualityBadge) -> String = { badge -> qualityBadgeLabel(badge) },
): DiscordQualityCard? {
    val badge = quality?.settledBadge?.let { "🎧 ${label(it)}" }
    val specs = format?.let { measured ->
        buildList {
            measured.codecLabel?.let(::add)
            measured.statedKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
            measured.bitDepth?.takeIf { it > 0 }?.let { add("$it-bit") }
            measured.sampleRateHz?.takeIf { it > 0 }?.let { add(sampleRateLabel(it)) }
        }.joinToString(" · ").ifBlank { null }
    }

    // No badge and no measured field: the decoder has not said anything worth
    // putting on the card yet, and an empty or invented line would be worse
    // than the album it replaces.
    if (badge == null && specs == null) return null

    return DiscordQualityCard(badge = badge, specs = specs)
}

/** The one line sent as `assets.large_text`, or null when there is nothing to say. */
internal fun discordAudioQualityLine(
    format: DesktopStreamFormat?,
    quality: QualityBadgeState?,
    label: (QualityBadge) -> String = { badge -> qualityBadgeLabel(badge) },
): String? = discordQualityCard(format, quality, label)?.line

/**
 * The card's third information line: the settled measured quality while its
 * switch is on and the measured stream has a label or a field to show, the
 * album otherwise. A transient upgrade state never contributes a word here;
 * the measured stream's own line is what stays up while the search runs.
 */
internal fun discordThirdLine(
    format: DesktopStreamFormat?,
    quality: QualityBadgeState?,
    showQuality: Boolean,
    album: String?,
    label: (QualityBadge) -> String = { badge -> qualityBadgeLabel(badge) },
): String? = discordAudioQualityLine(format, quality, label).takeIf { showQuality } ?: album

/** DSD rates are named in MHz; everything else reads better in kHz. */
private fun sampleRateLabel(hz: Int): String =
    NerdStats.megahertzLabel(hz)
        ?: (String.format(Locale.ROOT, "%.1f", hz / 1000f).removeSuffix(".0") + " kHz")

/** Discord caps each activity text field at 128 characters. */
private const val DISCORD_TEXT_LIMIT = 128
