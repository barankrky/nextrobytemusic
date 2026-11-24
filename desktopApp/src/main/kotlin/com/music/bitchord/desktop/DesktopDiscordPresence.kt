package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/**
 * The card settings as one snapshot.
 *
 * Read together and passed around as a value, so a push cannot mix a new name
 * with an old switch, and so the card's wording can be built — and tested — with
 * no settings store in the way.
 */
internal data class DiscordCardSettings(
    val useDetails: Boolean = DesktopDiscordSettings.DEFAULT_USE_DETAILS,
    val status: String = DesktopDiscordSettings.DEFAULT_STATUS,
    val activityType: String = DesktopDiscordSettings.DEFAULT_ACTIVITY,
    /** The stored custom name; blank means the app's own name. */
    val activityName: String = "",
    val button1Text: String = "",
    val button1Visible: Boolean = DesktopDiscordSettings.DEFAULT_BUTTON_VISIBLE,
    val button2Text: String = "",
    val button2Visible: Boolean = DesktopDiscordSettings.DEFAULT_BUTTON_VISIBLE,
    val showAudioQuality: Boolean = DesktopDiscordSettings.DEFAULT_SHOW_AUDIO_QUALITY,
) {
    /**
     * What Discord puts after the verb.
     *
     * Never blank: Discord draws whatever `name` the activity carries, and a
     * missing one made the card read "Listening to" with nothing behind it.
     */
    val heading: String
        get() = activityName.ifBlank { DesktopDiscordSettings.APP_NAME }
}

/**
 * One presence, built once so the local socket and the account gateway cannot
 * drift apart in what they say.
 *
 * The timestamps are wall-clock instants with the playback rate divided out:
 * Discord counts the bar down on its own clock, so at 1.5x the wall-clock time
 * left is not the media time left, and a presence that ignored the rate would
 * finish its countdown mid-song.
 */
internal data class DiscordPresence(
    val key: String,
    val title: String,
    val artist: String,
    val heading: String,
    val thirdLine: String?,
    val status: String,
    val useDetails: Boolean,
    val activityType: String,
    val buttons: List<Pair<String, String>>,
    val artworkUrl: String,
    val watchUrl: String,
    val startMs: Long,
    val endMs: Long,
    val sinceMs: Long,
) {
    /**
     * The activity in the shape the local socket's SET_ACTIVITY takes.
     *
     * `name` is always sent: Discord attributes the activity to the application
     * id, but the wording on the profile comes from this field, and an activity
     * that omitted it while the stored custom name was blank showed Discord's
     * own name for the application instead of ours.
     *
     * `status_display_type` is Discord's number for the line it bolds: 1 is the
     * state (the artist), 2 the details (the song). It used to be the other way
     * round here, which made "Lead with the song" work on the account route and
     * do the opposite on the local one.
     */
    fun ipcActivity(): JsonObject = buildJsonObject {
        put("type", DesktopDiscordSettings.activityNumber(activityType))
        put("name", heading)
        put("details", title)
        put("state", artist)
        put("status_display_type", if (useDetails) 2 else 1)
        putJsonObject("timestamps") {
            put("start", startMs)
            put("end", endMs)
        }
        putJsonObject("assets") {
            put("large_image", artworkUrl)
            thirdLine?.takeIf { it.isNotBlank() }?.let { put("large_text", it) }
        }
        if (buttons.isNotEmpty()) {
            putJsonArray("buttons") {
                buttons.forEach { (label, url) ->
                    add(buildJsonObject { put("label", label); put("url", url) })
                }
            }
        }
    }
}

/**
 * The card for one moment of playback.
 *
 * Pure on purpose: the third line's rules (measured quality when switched on
 * and understood, album otherwise) and the countdown arithmetic are the parts
 * that break quietly, and they are the parts worth a test without a Discord.
 */
internal fun discordPresence(
    song: Song,
    positionMs: Long,
    durationMs: Long,
    format: DesktopStreamFormat?,
    speed: Float,
    settings: DiscordCardSettings,
    nowMs: Long,
    label: (DiscordQualityBadge) -> String = { it.label },
): DiscordPresence {
    val rate = speed.takeIf { it > 0f } ?: 1f
    val elapsed = (positionMs.coerceAtLeast(0L) / rate).toLong()
    val remaining = ((durationMs - positionMs).coerceAtLeast(0L) / rate).toLong()
    val title = if (rate != 1.0f) {
        "${song.title} [${String.format(Locale.ROOT, "%.2fx", rate)}]"
    } else {
        song.title
    }
    val thirdLine = discordThirdLine(format, settings.showAudioQuality, song.albumName, label)
    val artwork = song.artworkAt(ART_PX)?.takeIf { it.startsWith("http") } ?: FALLBACK_ART_URL
    val watch = "https://music.youtube.com/watch?v=${song.videoId}"
    val buttons = buildList<Pair<String, String>> {
        if (settings.button1Visible) {
            val text = settings.button1Text.ifEmpty { DesktopDiscordSettings.DEFAULT_BUTTON_1 }
            add(
                DesktopDiscordSettings.resolveVariables(text, song.title, song.artist, song.albumName) to watch,
            )
        }
        if (settings.button2Visible) {
            val text = settings.button2Text.ifEmpty { DesktopDiscordSettings.DEFAULT_BUTTON_2 }
            add(
                DesktopDiscordSettings.resolveVariables(text, song.title, song.artist, song.albumName) to PROJECT_URL,
            )
        }
    }
    // Everything Discord draws, and nothing it counts by itself: the start and
    // end instants move on every rebuild, so leaving them out is what makes two
    // pushes of the same card compare equal.
    val key = listOf(
        song.videoId,
        title,
        song.artist,
        thirdLine.orEmpty(),
        settings.heading,
        settings.status,
        settings.activityType,
        settings.useDetails.toString(),
        buttons.joinToString("\u0001") { "${it.first}\u0002${it.second}" },
        artwork,
        durationMs.toString(),
        rate.toString(),
    ).joinToString("\u0000")

    return DiscordPresence(
        key = key,
        title = title,
        artist = song.artist,
        heading = settings.heading,
        thirdLine = thirdLine,
        status = settings.status,
        useDetails = settings.useDetails,
        activityType = settings.activityType,
        buttons = buttons,
        artworkUrl = artwork,
        watchUrl = watch,
        startMs = nowMs - elapsed,
        endMs = nowMs + remaining,
        sinceMs = nowMs,
    )
}

/** What one playback update asks the transport to do. */
internal sealed interface DiscordPresenceAction {
    /** The switch is off: nothing is sent and nothing is cleared. */
    data object Idle : DiscordPresenceAction

    /** Nothing is playing: take the card down. */
    data object Clear : DiscordPresenceAction

    data class Show(val presence: DiscordPresence) : DiscordPresenceAction
}

/**
 * The playhead as it is now, rather than as it was when the UI last looked.
 *
 * A settings change republishes a card that was built from the last playback
 * snapshot the window saw — which can be minutes old, because the snapshot only
 * changes when the track, its state or its format does. Taking its position
 * verbatim put the progress bar back at the start of the song every time a
 * switch was touched. The state carries the instant its position was read on
 * the audio thread for exactly this reason, so the playhead can be advanced to
 * the present. Paused playback is left where it stopped.
 */
internal fun projectedPositionMs(
    state: DesktopPlaybackState,
    speed: Float,
    nowNanos: Long,
): Long {
    if (!state.isPlaying) return state.positionMs
    val sampled = state.positionSampledAtNanos
    if (sampled <= 0L) return state.positionMs
    val rate = speed.takeIf { it > 0f } ?: 1f
    val elapsedMs = ((nowNanos - sampled) / 1_000_000L).coerceAtLeast(0L)
    val advanced = state.positionMs + (elapsedMs * rate).toLong()
    val limit = state.durationMs.takeIf { it > 0L }
    return limit?.let { advanced.coerceAtMost(it) } ?: advanced
}

/**
 * The switch's promise, as a value: off is silent, stopped is cleared, playing
 * is a card.
 */
internal fun discordPresenceAction(
    enabled: Boolean,
    state: DesktopPlaybackState?,
    speed: Float,
    settings: DiscordCardSettings,
    nowMs: Long,
): DiscordPresenceAction {
    if (!enabled) return DiscordPresenceAction.Idle
    val song = state?.song
    if (state == null || song == null || !state.isPlaying) return DiscordPresenceAction.Clear
    return DiscordPresenceAction.Show(
        discordPresence(song, state.positionMs, state.durationMs, state.streamFormat, speed, settings, nowMs),
    )
}

/**
 * Remembers the card Discord was last told, and only sends when it differs.
 *
 * The key is recorded after a send reports success, never before it. Recording
 * it first — which is what the desktop used to do — meant a socket that had
 * just gone away consumed the update, and the same card was then never sent
 * again until the next track. [forget] is called whenever the transport
 * changes, because a new connection has been told nothing.
 */
internal class DiscordPresenceCache(
    private val send: suspend (DiscordPresence) -> Boolean,
    private val clearTransport: suspend () -> Unit,
) {
    private var lastKey: String? = null

    /** Whether a card is believed to be showing through the current transport. */
    val showing: Boolean
        get() = lastKey != null

    /** The transport changed: it holds no card of ours, whatever Discord still shows. */
    fun forget() {
        lastKey = null
    }

    /**
     * Sends [presence] unless it is what Discord is already showing, or
     * [force] asks for the countdown to be restarted.
     *
     * Returns whether a send was attempted and taken.
     */
    suspend fun push(presence: DiscordPresence, force: Boolean = false): Boolean {
        if (!force && presence.key == lastKey) return false
        if (!send(presence)) return false
        lastKey = presence.key
        return true
    }

    /** Takes the card down; a no-op once nothing of ours is showing. */
    suspend fun clear() {
        val wasShowing = lastKey != null
        lastKey = null
        if (wasShowing) clearTransport()
    }
}

private const val ART_PX = 480
private const val PROJECT_URL = "https://github.com/barankrky/nextrobytemusic"
private const val FALLBACK_ART_URL =
    "https://raw.githubusercontent.com/barankrky/nextrobytemusic/main/app/src/main/ic_launcher-playstore.png"
