package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What an addon's `allowDownloads: 0` does — and does not — decide on the desktop.
 *
 * The manifest switch marks an addon streaming-only for clients that honour it.
 * nextrobytemusic records it, and an explicit download still saves that addon's
 * stream: the download walk is the ordinary source walk, so a track queued from
 * a streaming-only addon is filed from that addon rather than quietly handed to
 * whoever is next in line.
 *
 * Worth its own test because the rule is expressed as an absence — nothing
 * filters on the flag — and an absence is exactly what a later "let's honour it
 * properly" change would restore without noticing it was deliberate. The phone
 * has the same test in `DownloadSourcePolicyTest`.
 */
class DesktopDownloadSourcePolicyTest {

    private fun addon(id: String, allowed: Boolean) = DesktopSourceConfig(
        id = id,
        kind = DesktopSourceKind.ADDON,
        baseUrl = "https://$id.example.com",
        allowDownloads = allowed,
    )

    /** The switch is stored, because the manifest's answer is worth reading back — but the walk ignores it. */
    @Test
    fun `a streaming-only addon is still a download source`() {
        val streamingOnly = addon("streaming-only", allowed = false)

        val walk = DesktopSourceRegistry.downloadWalk(listOf(streamingOnly))

        assertEquals(
            listOf(streamingOnly.id),
            walk.map { it.id },
            "the addon was skipped by the download walk",
        )
    }

    /** The next source in line is not asked first just because this one said no. */
    @Test
    fun `a streaming-only addon keeps its place in the download order`() {
        val first = addon("first", allowed = false)
        val second = addon("second", allowed = true)

        assertEquals(
            listOf(first.id, second.id),
            DesktopSourceRegistry.downloadWalk(listOf(first, second)).map { it.id },
        )
    }
}
