package com.music.bitchord

import androidx.lifecycle.Lifecycle
import com.music.bitchord.ui.foregroundLifecycleFloor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What the desktop lyrics clock treats as "on screen".
 *
 * Compose's desktop window container reports RESUMED only while the window holds
 * focus and STARTED for a window that is visible but unfocused. The clock used
 * to gate on RESUMED, so clicking another window froze every lyric on a panel
 * that was still in view and a track that was still playing. The floor is
 * STARTED on this target for that reason; the other half of the rule is that a
 * minimized or hidden window drops to CREATED, which is below the floor, so the
 * work still stops when nothing can be seen.
 */
class ForegroundStateTest {

    @Test
    fun `a visible but unfocused desktop window still counts as on screen`() {
        assertEquals(Lifecycle.State.STARTED, foregroundLifecycleFloor)
    }

    @Test
    fun `a minimized or hidden desktop window is below the floor`() {
        assertFalse(Lifecycle.State.CREATED.isAtLeast(foregroundLifecycleFloor))
    }
}
