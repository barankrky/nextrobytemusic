package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether YouTube's own stream may be asked for the Premium-only Opus ladder.
 *
 * Android keeps this in `AppSettings.youtubePremiumAudio` and reads it through
 * `AndroidStreamHooks`; the desktop has no AppSettings, so the flag lives here and
 * [StreamResolver.premiumAudio] is pointed at [enabled] in `main`.
 *
 * Off by default: asking a non-Premium account for the higher ladder does not fail
 * cleanly on every client, it can answer with a format the account is not entitled
 * to and the player then cannot open. The user states the entitlement rather than
 * the app guessing it from the signed-in account.
 */
internal object DesktopYoutubePremium {

    private val _enabled = MutableStateFlow(DesktopPersistence().boolean(KEY_ENABLED, false))

    /** The toggle as the settings page and the resolver both read it. */
    val enabled: StateFlow<Boolean> = _enabled

    fun setEnabled(value: Boolean) {
        DesktopPersistence().saveBoolean(KEY_ENABLED, value)
        _enabled.value = value
    }

    internal const val KEY_ENABLED = "youtube_premium_audio"
}
