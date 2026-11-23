package com.music.bitchord.data.sources

import java.util.Locale

/**
 * What a source is about to hand the decoder, as far as the source will say.
 *
 * Every field is nullable because most of them are genuinely unknown until the
 * bytes arrive: a server that reports "flac" rarely reports the bit depth with
 * it, and one that reports a bitrate is usually describing a transcode it has
 * not performed yet. Nothing here is inferred — a null means "not stated", and
 * the player reports the decoder's own numbers over these once it has them.
 * The two are worth comparing rather than merging, because a source claiming
 * 24/192 and a sink running at 16/48 is exactly the failure this feature is
 * most likely to hide.
 */
data class StreamFormat(
    /** Container/codec as the source names it, lowercased: `flac`, `opus`, `mp3`. */
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    /**
     * Whether the bytes are bit-exact, for the paths that know it from a MIME
     * type rather than a codec name.
     *
     * A track playing from the disk cache has no resolve behind it and no
     * source-declared codec to carry; the decoder's `audio/flac` does say the
     * stream is lossless, and dropping that fact made [isLossless] null and the
     * cached-track upgrade path compare a FLAC against nothing. Null leaves the
     * codec to decide, which is the only answer a source's own declaration can
     * give.
     */
    val lossless: Boolean? = null,
) {
    /**
     * Whether this is a bit-exact copy of the master the source holds.
     *
     * Decided on the codec alone. A lossless codec at any bitrate is lossless;
     * a lossy one at any bitrate is not, and no sample rate rescues it — a
     * 192kHz Opus stream is still Opus. Unknown codec means unknown, not false,
     * so callers that care have to say what they want done about it.
     */
    val isLossless: Boolean?
        get() = lossless ?: codec?.let { it in LOSSLESS_CODECS }

    /** Dolby Atmos carried in E-AC-3 JOC. This is immersive, but not lossless. */
    val isDolbyAtmos: Boolean
        get() = codec in DOLBY_ATMOS_CODECS

    /**
     * Better than CD: past 16-bit or past 48kHz, and genuinely lossless.
     *
     * The bar Tidal, Qobuz and Apple Music draw. A cached copy at or under it is
     * still worth asking the sources about; one above it is not, because nothing
     * in this app's catalogues can name a better master.
     */
    val isHiRes: Boolean
        get() = isLossless == true && ((bitDepth ?: 0) > 16 || (sampleRateHz ?: 0) > 48_000)

    /**
     * How good a lossless rendition is, for choosing between two of them.
     *
     * Bit depth first, then sample rate, with CD defaults for anything not
     * stated. This is what keeps a 16-bit/44.1kHz FLAC from being swapped for
     * the same master another source listed under a different row.
     */
    val losslessRank: Int
        get() = (bitDepth ?: 16) * 1_000_000 + (sampleRateHz ?: 44_100)

    /** "24-bit · 192 kHz", "FLAC", "320 kbps" — whichever parts are known. */
    val summary: String
        get() = if (isDolbyAtmos) "Dolby Atmos" else listOfNotNull(
            codec?.uppercase(Locale.ROOT),
            bitDepth?.let { "$it-bit" },
            sampleRateHz?.let { "${"%.1f".format(Locale.ROOT, it / 1000f).removeSuffix(".0")} kHz" },
            kbps?.takeIf { isLossless != true }?.let { "$it kbps" },
        ).joinToString(" · ").ifEmpty { "Unknown format" }

    private companion object {
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "ape", "wv", "dsf", "dff")
        val DOLBY_ATMOS_CODECS = setOf("eac3-joc", "ec3-joc", "dolby-atmos")
    }
}
