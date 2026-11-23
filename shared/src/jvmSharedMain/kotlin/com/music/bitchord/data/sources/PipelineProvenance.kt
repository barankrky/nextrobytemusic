package com.music.bitchord.data.sources

/** Where a playing track's bytes are coming from right now. */
enum class PipelinePlaybackStatus {
    /** Every byte is on disk — a completed cache entry or a downloaded file. */
    CACHED,

    /** The player is still reading from the network. */
    STREAMING,

    /** The listener's own file, never served by any configured source. */
    LOCAL,
}

/**
 * The three track-info facts the audio pipeline screen states about playback:
 * who supplied the recording, which upstream behind that supplier answered,
 * and whether the bytes are still coming off the network.
 */
data class PipelineSourceInfo(
    val name: String?,
    val provider: String? = null,
    val status: PipelinePlaybackStatus? = null,
)

/**
 * Turns the several places provenance is kept into the one row set the
 * pipeline screen draws.
 *
 * A track can be described by up to five independent records, and which one is
 * worth believing depends on how it is playing: the cache entry names the
 * source that filled it even when the queue item no longer does; a download
 * record outlives the server it came from; the source-track key says where the
 * row was queued; the live resolver records what actually answered; and a
 * plain local file has none of them. This function is the precedence rule, in
 * one place, so the phone and the desktop cannot drift.
 */
object PipelineProvenance {

    /** The source label the pipeline shows for a file that no source supplied. */
    const val LOCAL_LABEL = "Local Storage"

    /**
     * @param local the item is being read from a `file://`, `content://` or
     *   local path rather than through the player's HTTP path.
     * @param downloaded a download record names this track, which makes a
     *   local file a saved copy rather than the listener's own import.
     * @param cacheComplete whether the cache entry being played is complete;
     *   null when there is no cache record to ask.
     * @param addonTrackId the addon's own id for the track, when the queue key
     *   still carries it — the fallback provider source for gateways that do
     *   not send [AddonUpstreams.HEADER].
     */
    fun resolve(
        mediaId: String?,
        local: Boolean,
        downloaded: Boolean,
        downloadSourceName: String? = null,
        downloadProvider: String? = null,
        cacheSourceName: String? = null,
        cacheProvider: String? = null,
        cacheComplete: Boolean? = null,
        addonTrackId: String? = null,
        sourceTrackName: String? = null,
        recordedSourceName: String? = null,
        recordedProvider: String? = null,
        fallbackSourceName: String? = null,
    ): PipelineSourceInfo {
        // The cache entry describes the bytes in hand, so it outranks a
        // download record (which may name a file that no longer exists), which
        // outranks the queue key, which outranks a live resolver's memory.
        val name = listOfNotNull(
            cacheSourceName,
            downloadSourceName,
            sourceTrackName,
            recordedSourceName,
        ).firstOrNull { it.isNotBlank() }
            ?: when {
                local || downloaded -> LOCAL_LABEL
                else -> fallbackSourceName?.takeIf { it.isNotBlank() }
            }

        // The header is recorded alongside whichever of those knew it. The
        // id is the fallback for a gateway that has not been redeployed yet.
        val provider = AddonUpstreams.displayName(
            cacheProvider
                ?: downloadProvider
                ?: AddonUpstreams.fromTrackId(addonTrackId)
                ?: recordedProvider,
        )

        val status = when {
            downloaded || cacheComplete == true -> PipelinePlaybackStatus.CACHED
            local -> PipelinePlaybackStatus.LOCAL
            mediaId != null -> PipelinePlaybackStatus.STREAMING
            else -> null
        }
        return PipelineSourceInfo(name = name, provider = provider, status = status)
    }
}
