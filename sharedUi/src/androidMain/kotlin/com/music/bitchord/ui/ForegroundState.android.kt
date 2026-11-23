package com.music.bitchord.ui

import androidx.lifecycle.Lifecycle

/**
 * RESUMED: a paused-but-visible activity is behind a dialog or in the
 * background half of split screen, and neither is worth animating for.
 */
internal actual val foregroundLifecycleFloor: Lifecycle.State = Lifecycle.State.RESUMED
