package com.music.bitchord.desktop

/**
 * The playback signal chain as it stands right now — what the engine can say about each stage
 * between the stream arriving and the samples reaching the device.
 *
 * Every field is nullable because the chain is only partly built until something is playing.
 */
internal data class DesktopAudioPipeline(
    /** What the engine is actually playing, which the screen's own copy can lag behind. */
    val sourceFormat: DesktopStreamFormat? = null,
    /** The configured source that supplied the bytes in hand, when known. */
    val sourceId: String? = null,
    /** The upstream behind an addon gateway that answered, lower case. */
    val provider: String? = null,
    /** True when the whole file is on disk — a completed cache entry or a download. */
    val cached: Boolean? = null,
    /** True for the listener's own file; no source supplied it. */
    val local: Boolean = false,
    val decoderName: String? = null,
    val decodedSampleRateHz: Int? = null,
    val decodedChannels: Int? = null,
    val outputSampleRateHz: Int? = null,
    val outputChannels: Int? = null,
    val outputIsFloat: Boolean? = null,
    val outputBytesPerSample: Int? = null,
    val deviceName: String? = null,
    val bufferBytes: Int = 0,
    val equalizerEnabled: Boolean = false,
    val skipSilence: Boolean = false,
) {
    /** The decoder under its usual name, the same one the quality badge shows. */
    val decoderLabel: String?
        get() = decoderName?.let { DesktopStreamFormat(codec = it).codecLabel }

    /** Whether the decoder's rate had to be converted to reach the device. */
    val resampling: Boolean
        get() = decodedSampleRateHz != null && outputSampleRateHz != null &&
            decodedSampleRateHz != outputSampleRateHz

    companion object {
        /**
         * The track-side fields from the stream the engine opened, kept here
         * so the readout and its tests read the same answer: a cached file
         * that came from the gateway is still the gateway's, and a download
         * is on disk whatever source supplied it.
         */
        fun of(stream: DesktopStream?): DesktopAudioPipeline = DesktopAudioPipeline(
            sourceFormat = stream?.format,
            sourceId = stream?.sourceId,
            provider = stream?.provider,
            cached = stream?.cached,
            local = stream?.local == true,
        )
    }
}
