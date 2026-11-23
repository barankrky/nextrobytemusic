package com.music.bitchord.ui

import androidx.lifecycle.Lifecycle

/**
 * STARTED, not RESUMED: Compose's desktop window container reports RESUMED only
 * while the window holds focus and STARTED for a window that is visible but
 * unfocused. The lyric clock has to keep running in the second case — the panel
 * is still in view and the track is still playing — so focus loss must not read
 * as leaving the screen. A minimized or hidden window still drops to CREATED,
 * which is below STARTED and does stop the work.
 */
internal actual val foregroundLifecycleFloor: Lifecycle.State = Lifecycle.State.STARTED
