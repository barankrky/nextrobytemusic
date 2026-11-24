package com.music.bitchord.desktop

import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import com.my.kizzy.rpc.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Publishes what is playing to Discord as a Rich Presence activity.
 *
 * Two routes, and the desktop gets the good one Android cannot have.
 *
 * **The local socket, preferred.** A Discord client running on this machine
 * listens on a named pipe, and an application sets a presence through it with
 * nothing but its own application id — no token, no login, no impersonation.
 * That is Discord's official Rich Presence mechanism and what every desktop
 * application uses. Android has no such client to talk to, which is the whole
 * reason its implementation looks the way it does.
 *
 * **The account gateway, as a fallback.** Android's route, ported: sign in as
 * the account and speak the client's own protocol, because on a phone there is
 * nothing else. It stays here for the case the socket cannot cover — Discord
 * not running locally, or running somewhere else — and it is offered the way
 * Android offers it, as the alternative rather than the front door, because it
 * hands an app full account access and account automation is against Discord's
 * terms.
 *
 * The switch is the whole feature, so the object owns its lifecycle rather than
 * waiting for the settings panel to be opened: with it on, [start] keeps a
 * socket attempt going, notices a Discord that starts after the app, notices
 * one that quits, and republishes a card that a new connection never heard.
 * Everything transport-facing runs on one worker, so two pushes cannot write
 * interleaved frames into the same pipe.
 */
internal object DesktopDiscordRpc {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "desktop-discord-rpc").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + worker)

    @Volatile
    private var rpc: KizzyRPC? = null

    @Volatile
    private var ipc: DesktopDiscordIpc? = null

    /** The last playback state seen, kept so a switch change has something to publish. */
    @Volatile
    private var latest: DesktopPlaybackState? = null

    @Volatile
    private var latestSpeed: Float = 1f

    @Volatile
    private var latestSeeks: Int = -1

    private val cache = DiscordPresenceCache(
        send = ::deliver,
        clearTransport = ::deliverClear,
    )

    private val _localClient = MutableStateFlow(false)

    /** Whether a Discord on this machine is answering, so no token is needed. */
    val localClient: StateFlow<Boolean> = _localClient

    private val _username = MutableStateFlow(DesktopPersistence().string(KEY_USERNAME, ""))

    /** Who the stored token belongs to, for the settings row to name. */
    val username: StateFlow<String> = _username

    private val _enabled = MutableStateFlow(
        DesktopPersistence().boolean(KEY_ENABLED, DesktopDiscordSettings.DEFAULT_RPC_ENABLED),
    )

    /** Whether the user wants the card shown; independent of whether Discord is running. */
    val enabled: StateFlow<Boolean> = _enabled

    private val _token = MutableStateFlow(DesktopPersistence().string(KEY_TOKEN, ""))
    val token: StateFlow<String> = _token

    @Volatile
    private var started = false

    /**
     * Starts the parts that do not depend on a screen: the settings observer and
     * the socket watcher.
     *
     * Called once from the app shell. Before this, the only connection attempt
     * happened when the settings panel was opened, which is why a card could be
     * missing from a whole listening session.
     */
    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch { observeCardSettings() }
        scope.launch { watchConnection() }
    }

    /** A settings panel opening is a good moment to look now rather than at the next tick. */
    fun refreshLocalClient() {
        scope.launch { watchOnce() }
    }

    /** Checks a token by asking Discord who it belongs to, then keeps it. */
    suspend fun signIn(candidate: String): Result<UserInfo> =
        KizzyRPC.getUserInfo(candidate, USER_AGENT, superProperties())
            .onSuccess { user ->
                DesktopPersistence().saveString(KEY_TOKEN, candidate)
                DesktopPersistence().saveString(KEY_USERNAME, user.name)
                _token.value = candidate
                _username.value = user.name
                setEnabled(true)
            }

    fun signOut() {
        setEnabled(false)
        DesktopPersistence().saveString(KEY_TOKEN, "")
        DesktopPersistence().saveString(KEY_USERNAME, "")
        _token.value = ""
        _username.value = ""
    }

    /**
     * The switch.
     *
     * On connects and publishes what is playing right away, because a card that
     * only appeared at the next track change made the switch look broken for the
     * length of a song. Off clears the card and closes the socket: leaving the
     * pipe open left Discord believing a presence could still arrive.
     */
    fun setEnabled(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY_ENABLED, value)
        _enabled.value = value
        scope.launch {
            if (value) {
                cache.forget()
                ensureLocal()
                publish(force = true)
            } else {
                cache.clear()
                closeGateway()
                dropLocal()
                DesktopTrackLog.log("discord: sharing off")
            }
        }
    }

    /**
     * Pushes the current track, or takes the presence down.
     *
     * Called on every playback change the UI is told about: the track, whether
     * it is playing, its length, the playback rate, the measured format and a
     * seek. The card's own key decides whether any of that is worth a frame;
     * a seek is not in the key and forces a push so the countdown restarts where
     * the listener jumped to.
     */
    fun onPlaybackStateChanged(state: DesktopPlaybackState, speed: Float) {
        val previous = latest
        val current = state.song
        val force = current != null &&
            current.videoId == previous?.song?.videoId &&
            state.seeks != latestSeeks
        latest = state
        latestSpeed = speed
        latestSeeks = state.seeks
        if (!_enabled.value) return
        scope.launch { publish(force = force) }
    }

    /**
     * The card settings are inputs to the presence, not to the next track.
     *
     * Every one of them is watched, including the customize switch: turning it
     * off changes nothing about the wording, but the push keeps the presence in
     * step with the panel the user is looking at and is cheap compared to a
     * card that appears to be stuck.
     */
    private suspend fun observeCardSettings() {
        val settings = DesktopDiscordSettings
        combine<Any, List<Any>>(
            settings.useDetails,
            settings.advancedMode,
            settings.status,
            settings.activityType,
            settings.activityName,
            settings.button1Text,
            settings.button1Visible,
            settings.button2Text,
            settings.button2Visible,
            settings.showAudioQuality,
        ) { it.toList() }
            .drop(1)
            .collect {
                if (!_enabled.value) return@collect
                publish(force = true)
            }
    }

    /**
     * Keeps an eye on the local client.
     *
     * Discord can start after this app, quit while it plays, or restart after an
     * update. A missing socket is retried, and one that stopped answering is
     * dropped and replaced — the presence is then republished, because a new
     * pipe has been told nothing.
     */
    private suspend fun watchConnection() {
        while (currentCoroutineContext().isActive) {
            runCatching { watchOnce() }.onFailure {
                // One line rather than a stack: the reasons are ordinary — a
                // pipe that vanished mid-handshake, a client that just quit —
                // and the next tick is the retry.
                DesktopTrackLog.log("discord: watcher could not reach the client: ${it.message}")
            }
            delay(CONNECTION_POLL_MS)
        }
    }

    private suspend fun watchOnce() {
        if (!_enabled.value) return
        val local = ipc
        if (local != null) {
            val alive = withTimeoutOrNull(PING_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { local.ping() }
            } ?: false
            if (!alive) {
                DesktopTrackLog.log("discord: local client stopped answering; reconnecting")
                dropLocal()
            }
        }
        if (ipc == null) {
            if (ensureLocal() == null) return
        }
        publish()
    }

    /** Builds the card and lets [cache] decide whether Discord needs it. */
    private suspend fun publish(force: Boolean = false) {
        val nowNanos = System.nanoTime()
        val state = latest?.let {
            it.copy(positionMs = projectedPositionMs(it, latestSpeed, nowNanos))
        }
        val action = discordPresenceAction(
            enabled = _enabled.value,
            state = state,
            speed = latestSpeed,
            settings = DesktopDiscordSettings.cardSettings(),
            nowMs = System.currentTimeMillis(),
        )
        when (action) {
            DiscordPresenceAction.Idle -> Unit
            DiscordPresenceAction.Clear -> cache.clear()
            is DiscordPresenceAction.Show -> cache.push(action.presence, force)
        }
    }

    /** One local client, connected if it was not already. */
    private fun ensureLocal(): DesktopDiscordIpc? {
        ipc?.let { return it }
        val found = runCatching { DesktopDiscordIpc.connect(APPLICATION_ID) }.getOrNull() ?: return null
        ipc = found
        _localClient.value = true
        // A fresh pipe has been told nothing, whatever the previous one knew.
        cache.forget()
        DesktopTrackLog.log("discord: connected to the local client")
        return found
    }

    private fun dropLocal() {
        val local = ipc ?: return
        ipc = null
        _localClient.value = false
        cache.forget()
        runCatching { local.close() }
    }

    /**
     * Sends the card, local socket first.
     *
     * A failed write means the client quit or restarted; the socket is
     * discarded and one immediate reconnect is tried, because the common case is
     * a Discord that has just come back and a track that did not change. The
     * gateway is only asked when no local client can be reached at all.
     */
    private suspend fun deliver(presence: DiscordPresence): Boolean {
        var local = ensureLocal()
        if (local != null) {
            if (runCatching { local.setActivity(presence.ipcActivity()) }.isSuccess) {
                DesktopTrackLog.log(
                    "discord: published '${presence.title}' to the local client" +
                        presence.thirdLine?.let { line -> " · $line" }.orEmpty(),
                )
                return true
            }
            dropLocal()
            local = ensureLocal()
            if (local != null && runCatching { local.setActivity(presence.ipcActivity()) }.isSuccess) {
                DesktopTrackLog.log("discord: republished '${presence.title}' after reconnecting")
                return true
            }
        }

        val token = _token.value
        if (token.isBlank()) {
            DesktopTrackLog.log("discord: no local client and no token; the card waits for one")
            return false
        }
        return deliverViaGateway(presence, token)
    }

    private suspend fun deliverViaGateway(presence: DiscordPresence, token: String): Boolean =
        runCatching {
            val client = rpc ?: KizzyRPC(
                token = token,
                os = if (DesktopPlatform.isWindows) "Windows" else "Linux",
                browser = "Discord Client",
                device = "desktop",
                userAgent = USER_AGENT,
                superPropertiesBase64 = superProperties(),
            ).also { rpc = it }

            client.setActivity(
                name = presence.heading,
                details = presence.title,
                state = presence.artist,
                detailsUrl = presence.watchUrl,
                // Never null: an activity with no large image falls back to
                // whatever icon the application id points at, which is not
                // ours to set.
                largeImage = RpcImage.ExternalImage(presence.artworkUrl),
                smallImage = null,
                largeText = presence.thirdLine,
                buttons = presence.buttons.takeIf { it.isNotEmpty() },
                type = when (presence.activityType) {
                    "playing" -> KizzyRPC.Type.PLAYING
                    "watching" -> KizzyRPC.Type.WATCHING
                    "competing" -> KizzyRPC.Type.COMPETING
                    else -> KizzyRPC.Type.LISTENING
                },
                statusDisplayType = if (presence.useDetails) {
                    KizzyRPC.StatusDisplayType.DETAILS
                } else {
                    KizzyRPC.StatusDisplayType.STATE
                },
                since = presence.sinceMs,
                startTime = presence.startMs,
                endTime = presence.endMs,
                applicationId = APPLICATION_ID,
                status = presence.status,
            )
            DesktopTrackLog.log("discord: published '${presence.title}' through the account gateway")
        }.onFailure {
            // The message is Discord's, and never carries the token.
            DesktopTrackLog.log("discord: gateway update failed: ${it.message}")
        }.isSuccess

    /** Takes the presence down without closing the connection, so resuming is cheap. */
    private suspend fun deliverClear() {
        val local = ipc
        if (local != null) runCatching { local.setActivity(null) }
        val client = rpc
        if (client != null) {
            runCatching { client.close() }
        }
    }

    private suspend fun closeGateway() {
        val client = rpc ?: return
        rpc = null
        runCatching { client.close() }
        runCatching { withContext(Dispatchers.IO) { client.closeRPC() } }
    }

    /**
     * The `X-Super-Properties` header Discord's own client sends.
     *
     * The gateway accepts a presence without it, but the endpoint that mirrors
     * external artwork onto Discord's CDN starts refusing a request with no
     * client fingerprint — so the cover art quietly stops appearing while
     * everything else keeps working. Built once, because rotating the ids per
     * request is what a real client never does.
     */
    private val superProperties: String by lazy {
        val fields = mapOf(
            "os" to if (DesktopPlatform.isWindows) "Windows" else "Linux",
            "browser" to "Discord Client",
            "device" to "",
            "system_locale" to Locale.getDefault().toString(),
            "client_version" to CLIENT_VERSION,
            "release_channel" to "stable",
            "client_build_number" to CLIENT_BUILD,
            "device_vendor_id" to UUID.randomUUID().toString(),
            "client_uuid" to UUID.randomUUID().toString(),
            "client_launch_id" to UUID.randomUUID().toString(),
        )
        val json = JsonObject(
            fields.mapValues { (key, value) ->
                if (key == "client_build_number") JsonPrimitive(value.toInt()) else JsonPrimitive(value)
            },
        )
        Base64.getEncoder().encodeToString(Json.encodeToString(JsonObject.serializer(), json).toByteArray())
    }

    private fun superProperties(): String = superProperties

    private const val KEY_TOKEN = "discord_token"
    private const val KEY_USERNAME = "discord_username"
    private const val KEY_ENABLED = "discord_rpc_enabled"

    private const val APPLICATION_ID = "1574579619661750373"
    private const val CLIENT_VERSION = "0.0.83"
    private const val CLIENT_BUILD = "352675"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) discord/0.0.83 Chrome/120.0.0.0 Electron/28.2.10 Safari/537.36"

    /** How often a missing local client is looked for again. */
    private const val CONNECTION_POLL_MS = 5_000L

    /** A local client that cannot answer a ping in this long is treated as gone. */
    private const val PING_TIMEOUT_MS = 2_000L
}
