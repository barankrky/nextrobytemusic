package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How the Discord card is worded and what it carries.
 *
 * Keys and defaults are Android's, from `AppSettings` — with one deliberate
 * difference. Android starts with "Lead with the song" and both buttons on and
 * hides the card behind a token sign-in; the desktop's out-of-the-box card is
 * the one the product ships as its own: the app's name, the artist on the bold
 * line, no buttons, and the audio-quality line left for the listener to switch
 * on. Those are the values a fresh profile reads, and only a fresh profile: a
 * stored value always wins, so an existing card is never reset by an update.
 */
internal object DesktopDiscordSettings {

    private val persistence = DesktopPersistence()

    private val _useDetails = MutableStateFlow(persistence.boolean(KEY_USE_DETAILS, DEFAULT_USE_DETAILS))
    private val _advancedMode = MutableStateFlow(persistence.boolean(KEY_ADVANCED, DEFAULT_ADVANCED_MODE))
    private val _status = MutableStateFlow(persistence.string(KEY_STATUS, "").ifBlank { DEFAULT_STATUS })
    private val _activityType = MutableStateFlow(persistence.string(KEY_ACTIVITY_TYPE, "").ifBlank { DEFAULT_ACTIVITY })
    private val _activityName = MutableStateFlow(persistence.string(KEY_ACTIVITY_NAME, ""))
    private val _button1Text = MutableStateFlow(persistence.string(KEY_BUTTON_1_TEXT, ""))
    private val _button1Visible = MutableStateFlow(persistence.boolean(KEY_BUTTON_1_VISIBLE, DEFAULT_BUTTON_VISIBLE))
    private val _button2Text = MutableStateFlow(persistence.string(KEY_BUTTON_2_TEXT, ""))
    private val _button2Visible = MutableStateFlow(persistence.boolean(KEY_BUTTON_2_VISIBLE, DEFAULT_BUTTON_VISIBLE))
    private val _showAudioQuality = MutableStateFlow(persistence.boolean(KEY_SHOW_AUDIO_QUALITY, DEFAULT_SHOW_AUDIO_QUALITY))

    /** Whether the song goes on the bold line instead of the artist. */
    val useDetails: StateFlow<Boolean> = _useDetails
    val advancedMode: StateFlow<Boolean> = _advancedMode
    val status: StateFlow<String> = _status
    val activityType: StateFlow<String> = _activityType
    val activityName: StateFlow<String> = _activityName
    val button1Text: StateFlow<String> = _button1Text
    val button1Visible: StateFlow<Boolean> = _button1Visible
    val button2Text: StateFlow<String> = _button2Text
    val button2Visible: StateFlow<Boolean> = _button2Visible

    /** Whether the measured audio format replaces the album on the card's third line. */
    val showAudioQuality: StateFlow<Boolean> = _showAudioQuality

    fun setUseDetails(value: Boolean) = flag(_useDetails, KEY_USE_DETAILS, value)
    fun setAdvancedMode(value: Boolean) = flag(_advancedMode, KEY_ADVANCED, value)
    fun setButton1Visible(value: Boolean) = flag(_button1Visible, KEY_BUTTON_1_VISIBLE, value)
    fun setButton2Visible(value: Boolean) = flag(_button2Visible, KEY_BUTTON_2_VISIBLE, value)
    fun setShowAudioQuality(value: Boolean) = flag(_showAudioQuality, KEY_SHOW_AUDIO_QUALITY, value)

    fun setStatus(value: String) = text(_status, KEY_STATUS, value)
    fun setActivityType(value: String) = text(_activityType, KEY_ACTIVITY_TYPE, value)
    fun setActivityName(value: String) = text(_activityName, KEY_ACTIVITY_NAME, value)
    fun setButton1Text(value: String) = text(_button1Text, KEY_BUTTON_1_TEXT, value)
    fun setButton2Text(value: String) = text(_button2Text, KEY_BUTTON_2_TEXT, value)

    /** The card as it stands, read together so one push cannot mix two states. */
    fun cardSettings(): DiscordCardSettings = DiscordCardSettings(
        useDetails = _useDetails.value,
        status = _status.value,
        activityType = _activityType.value,
        activityName = _activityName.value,
        button1Text = _button1Text.value,
        button1Visible = _button1Visible.value,
        button2Text = _button2Text.value,
        button2Visible = _button2Visible.value,
        showAudioQuality = _showAudioQuality.value,
    )

    private fun flag(flow: MutableStateFlow<Boolean>, key: String, value: Boolean) {
        persistence.saveBoolean(key, value)
        flow.value = value
    }

    private fun text(flow: MutableStateFlow<String>, key: String, value: String) {
        persistence.saveString(key, value)
        flow.value = value
    }

    /** `{song_name}`, `{artist_name}` and `{album_name}`, filled in. */
    fun resolveVariables(text: String, title: String, artist: String, album: String?): String = text
        .replace("{song_name}", title)
        .replace("{artist_name}", artist)
        .replace("{album_name}", album.orEmpty())

    /** Discord's activity type numbers. */
    fun activityNumber(type: String): Int = when (type) {
        "playing" -> 0
        "watching" -> 3
        "competing" -> 5
        else -> 2
    }

    val statuses = listOf(
        Choice("online", "Online", "Green dot"),
        Choice("idle", "Idle", "Amber crescent, shown as away"),
        Choice("dnd", "Do not disturb", "Red dash. Also suppresses notifications."),
    )

    val activities = listOf(
        Choice("listening", "Listening", "Listening to"),
        Choice("playing", "Playing", "Playing"),
        Choice("watching", "Watching", "Watching"),
        Choice("competing", "Competing", "Competing in"),
    )

    /** One option in a choice list: what it is called and what it does. */
    internal data class Choice(val id: String, val label: String, val detail: String)

    fun statusLabel(id: String) = statuses.firstOrNull { it.id == id }?.label ?: id
    fun activityLabel(id: String) = activities.firstOrNull { it.id == id }?.label ?: id
    fun activityVerb(id: String) = activities.firstOrNull { it.id == id }?.detail ?: "Listening to"

    const val DEFAULT_BUTTON_1 = "Listen on YouTube Music"
    const val DEFAULT_BUTTON_2 = "Visit nextrobytemusic"
    const val APP_NAME = "nextrobytemusic"

    /**
     * What a fresh profile gets, and the card the product ships with.
     *
     * Kept as named constants rather than literals in the initialisers so a test
     * can pin the whole set without touching the user's stored preferences.
     */
    const val DEFAULT_RPC_ENABLED = true
    const val DEFAULT_USE_DETAILS = false
    const val DEFAULT_ADVANCED_MODE = true
    const val DEFAULT_BUTTON_VISIBLE = false
    const val DEFAULT_SHOW_AUDIO_QUALITY = false
    const val DEFAULT_STATUS = "online"
    const val DEFAULT_ACTIVITY = "listening"

    private const val KEY_USE_DETAILS = "discord_use_details"
    private const val KEY_ADVANCED = "discord_advanced_mode"
    private const val KEY_STATUS = "discord_status"
    private const val KEY_ACTIVITY_TYPE = "discord_activity_type"
    private const val KEY_ACTIVITY_NAME = "discord_activity_name"
    private const val KEY_BUTTON_1_TEXT = "discord_button_1_text"
    private const val KEY_BUTTON_1_VISIBLE = "discord_button_1_visible"
    private const val KEY_BUTTON_2_TEXT = "discord_button_2_text"
    private const val KEY_BUTTON_2_VISIBLE = "discord_button_2_visible"
    private const val KEY_SHOW_AUDIO_QUALITY = "discord_show_audio_quality"
}
