package com.music.bitchord

import com.music.bitchord.playback.AudioCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What survives the post-track cache purge.
 *
 * The rule used to drop every addon/module entry, which made a lossless FLAC
 * a fresh network fetch on every play. It is now the same rule as YouTube and
 * JioSaavn: played audio stays on disk until the LRU evictor takes it, and
 * only Automix's analysis-only copies are discarded. The phone has no easy
 * way to drive the real [AudioCache] from a plain JVM test, so the decision
 * itself is what is held here.
 */
class AudioCachePolicyTest {

    @Test
    fun `a played addon or module entry is kept`() {
        assertTrue(AudioCache.retainedOnDisk(AudioCache.Origin.OTHER, analysisOnly = false))
    }

    @Test
    fun `an analysis-only addon entry is still dropped`() {
        assertFalse(AudioCache.retainedOnDisk(AudioCache.Origin.OTHER, analysisOnly = true))
    }

    @Test
    fun `the older origins keep the same rule`() {
        assertTrue(AudioCache.retainedOnDisk(AudioCache.Origin.YOUTUBE, analysisOnly = false))
        assertTrue(AudioCache.retainedOnDisk(AudioCache.Origin.JIOSAAVN, analysisOnly = false))
        assertFalse(AudioCache.retainedOnDisk(AudioCache.Origin.YOUTUBE, analysisOnly = true))
        assertFalse(AudioCache.retainedOnDisk(AudioCache.Origin.JIOSAAVN, analysisOnly = true))
    }
}
