package com.music.bitchord.desktop

import com.music.bitchord.data.NerdStats
import java.util.Locale

/**
 * The optional audio-quality line on nextrobytemusic's Discord card.
 *
 * The figures are the decoder's measured ones from [DesktopPlaybackState.streamFormat],
 * never the quality a source merely advertised: a lossy fallback must not
 * continue calling itself Lossless on Discord. The player already prefers the
 * decoder's measurement over the source's claim when it publishes the state,
 * so reading that state is what keeps the two honest.
 *
 * Android draws this line only for premium formats (`DiscordAudioQuality.kt`).
 * The desktop shows the measured encoding for ordinary lossy streams too —
 * "Opus · 160 kbps" is exactly the kind of thing a local Discord card is read
 * for — and leaves the line absent until a decoder has measured something, so
 * a card never states a format it has not seen.
 */
internal fun discordAudioQualityLine(format: DesktopStreamFormat?): String? {
    format ?: return null

    val quality = when {
        format.isDolbyAtmos -> "◗◖ Dolby Atmos"
        format.isHiRes -> "Hi-Res Lossless"
        format.isLossless -> "Lossless"
        else -> null
    }

    val details = buildList {
        format.codecLabel?.let(::add)
        format.statedKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
        format.bitDepth?.takeIf { it > 0 }?.let { add("$it-bit") }
        format.sampleRateHz?.takeIf { it > 0 }?.let { add(sampleRateLabel(it)) }
        format.channels?.takeIf { it > 0 }?.let {
            add(
                when (it) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    else -> "$it ch"
                },
            )
        }
    }

    // No premium word and no measured field: the decoder has not said anything
    // worth putting on the card yet, and an empty or invented line would be
    // worse than the album it replaces.
    if (quality == null && details.isEmpty()) return null

    return (listOfNotNull(quality) + details)
        .joinToString(" · ")
        .take(DISCORD_TEXT_LIMIT)
        .trimEnd()
}

/**
 * The card's third information line: the measured quality while its switch is
 * on and the decoder has something to say, the album otherwise.
 */
internal fun discordThirdLine(
    format: DesktopStreamFormat?,
    showQuality: Boolean,
    album: String?,
): String? = discordAudioQualityLine(format).takeIf { showQuality } ?: album

/** DSD rates are named in MHz; everything else reads better in kHz. */
private fun sampleRateLabel(hz: Int): String =
    NerdStats.megahertzLabel(hz)
        ?: (String.format(Locale.ROOT, "%.1f", hz / 1000f).removeSuffix(".0") + " kHz")

/** Discord caps each activity text field at 128 characters. */
private const val DISCORD_TEXT_LIMIT = 128
