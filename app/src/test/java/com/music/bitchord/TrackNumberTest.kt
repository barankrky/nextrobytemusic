package com.music.bitchord

import com.music.bitchord.data.LocalMediaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The two shapes a track number reaches the scanner in. */
class TrackNumberTest {

    @Test
    fun `MediaStore packs disc and track into one int`() {
        assertEquals(3, LocalMediaRepository.trackNumberFromMediaStore(3))
        assertEquals(3, LocalMediaRepository.trackNumberFromMediaStore(1003))
        assertEquals(12, LocalMediaRepository.trackNumberFromMediaStore(2012))
        assertNull(LocalMediaRepository.trackNumberFromMediaStore(0))
        assertNull(LocalMediaRepository.trackNumberFromMediaStore(null))
    }

    @Test
    fun `a tag may carry a total after a slash`() {
        assertEquals(3, LocalMediaRepository.trackNumberFromTag("3"))
        assertEquals(3, LocalMediaRepository.trackNumberFromTag("3/12"))
        assertEquals(3, LocalMediaRepository.trackNumberFromTag(" 3 / 12 "))
        assertNull(LocalMediaRepository.trackNumberFromTag(null))
        assertNull(LocalMediaRepository.trackNumberFromTag("bonus"))
        assertNull(LocalMediaRepository.trackNumberFromTag("0"))
    }
}
