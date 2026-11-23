package com.music.bitchord.data.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two places a provider can come from: the gateway's response header, and
 * the id an older gateway minted. Both are read by the phone and the desktop
 * through this object, so the mapping is pinned here rather than in either app.
 */
class AddonUpstreamsTest {

    @Test
    fun `legacy track id prefixes name their upstream`() {
        assertEquals("arcod", AddonUpstreams.fromTrackId("ar_123456"))
        assertEquals("netease", AddonUpstreams.fromTrackId("gd_98765"))
        assertEquals("monochrome", AddonUpstreams.fromTrackId("td_abc"))
        assertEquals("bridge", AddonUpstreams.fromTrackId("ne_123"))
        assertEquals("bridge", AddonUpstreams.fromTrackId("qq_456"))
    }

    @Test
    fun `a namespaced id names its own addon`() {
        assertEquals("emika", AddonUpstreams.fromTrackId("emika:U2FsdGVkX19hYmM"))
        // The prefix is a name, not a case-sensitive token.
        assertEquals("emika", AddonUpstreams.fromTrackId("Emika:U2FsdGVkX19hYmM"))
    }

    @Test
    fun `a colon inside an unrelated id is not a provider`() {
        assertNull(AddonUpstreams.fromTrackId("tidal:417594671"))
        assertNull(AddonUpstreams.fromTrackId("al::bum::7"))
        assertNull(AddonUpstreams.fromTrackId(""))
        assertNull(AddonUpstreams.fromTrackId(null))
    }

    @Test
    fun `bridge is shown to listeners as lossless`() {
        assertEquals("bridge", AddonUpstreams.canonical("BRIDGE"))
        assertEquals("lossless", AddonUpstreams.displayName("bridge"))
        assertEquals("lossless", AddonUpstreams.displayName("Bridge"))
        assertEquals("arcod", AddonUpstreams.displayName("arcod"))
        assertNull(AddonUpstreams.displayName(null))
    }

    @Test
    fun `a stated header wins over the track id`() {
        assertEquals(
            "monochrome",
            AddonUpstreams.resolve("Monochrome", "gd_123"),
        )
        assertEquals(
            "netease",
            AddonUpstreams.resolve(null, "gd_123"),
        )
        // A header that is not a provider-shaped token is ignored rather than
        // painted on screen verbatim.
        assertEquals(
            "arcod",
            AddonUpstreams.resolve("not a provider!", "ar_123"),
        )
    }
}
