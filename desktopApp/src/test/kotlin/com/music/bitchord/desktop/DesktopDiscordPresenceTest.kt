package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop Discord card: the defaults a fresh profile gets, the line the
 * audio-quality switch adds, and the rules that decide when Discord is told
 * anything at all.
 *
 * No socket and no account are involved. The local socket's framing has its own
 * test; what is pinned here is the part that used to go wrong silently — a card
 * that was never sent because a failed send had already been recorded, and
 * settings that only reached Discord at the next track.
 *
 * The quality words come from the same catalogues the player's badge reads, and
 * the tests take them from those XML files directly instead of switching
 * [DesktopStrings]' language. The language in force belongs to whoever runs the
 * tests; a Turkish profile must not turn an English expectation red, or the
 * other way round.
 */
class DesktopDiscordPresenceTest {

    private val song = Song(
        videoId = "track-id",
        title = "A Song",
        artist = "An Artist",
        thumbnailUrl = "https://example.com/art.jpg",
        albumName = "An Album",
    )

    private fun presence(
        format: DesktopStreamFormat? = null,
        settings: DiscordCardSettings = DiscordCardSettings(),
        nowMs: Long = 1_000_000L,
        song: Song = this.song,
        label: (DiscordQualityBadge) -> String = catalogue("en"),
    ) = discordPresence(
        song = song,
        positionMs = 30_000L,
        durationMs = 210_000L,
        format = format,
        speed = 1f,
        settings = settings,
        nowMs = nowMs,
        label = label,
    )

    /**
     * The quality words as one of the shipped catalogues has them.
     *
     * Read from the resource rather than through [DesktopStrings.setLanguage]
     * so a test states the exact text it expects, in the language it names,
     * without touching the listener's stored choice.
     */
    private fun catalogue(tag: String): (DiscordQualityBadge) -> String {
        val stream = DesktopStrings::class.java.getResourceAsStream("/strings/$tag.xml")
        assertNotNull(stream, "no catalogue at /strings/$tag.xml")
        val entries = stream.use { input ->
            Regex("""<string name="([^"]+)">([^<]*)</string>""")
                .findAll(input.readBytes().decodeToString())
                .associate { it.groupValues[1] to DesktopStrings.unescape(it.groupValues[2]) }
        }
        return { badge -> entries[badge.key] ?: error("$tag has no ${badge.key}") }
    }

    // ── Defaults ─────────────────────────────────────────────────────────

    @Test
    fun `a fresh profile shares the app's own name with no buttons and no quality line`() {
        assertTrue(DesktopDiscordSettings.DEFAULT_RPC_ENABLED)
        assertFalse(DesktopDiscordSettings.DEFAULT_USE_DETAILS)
        assertTrue(DesktopDiscordSettings.DEFAULT_ADVANCED_MODE)
        assertFalse(DesktopDiscordSettings.DEFAULT_BUTTON_VISIBLE)
        assertFalse(DesktopDiscordSettings.DEFAULT_SHOW_AUDIO_QUALITY)
        assertEquals("online", DesktopDiscordSettings.DEFAULT_STATUS)
        assertEquals("listening", DesktopDiscordSettings.DEFAULT_ACTIVITY)

        // The name Discord draws when the stored one is blank.
        assertEquals("nextrobytemusic", DesktopDiscordSettings.APP_NAME)
        assertEquals("nextrobytemusic", DiscordCardSettings().heading)
    }

    // ── The quality line ─────────────────────────────────────────────────

    @Test
    fun `the English card is one line with the player badge first`() {
        val format = DesktopStreamFormat(
            codec = "flac",
            sampleRateHz = 48_000,
            bitDepth = 24,
            channels = 2,
        )
        val line = assertNotNull(discordAudioQualityLine(format, catalogue("en")))
        assertEquals(
            "🎧 Hi-Res Lossless · FLAC · 2304 kbps · 24-bit · 48 kHz",
            line,
        )
        assertFalse(line.contains('\n'), "the card's third line is one line")
    }

    @Test
    fun `the Turkish card names the quality exactly as the player badge does`() {
        val format = DesktopStreamFormat(
            codec = "flac",
            sampleRateHz = 48_000,
            bitDepth = 24,
            channels = 2,
        )
        // The track in the report: 2304 kbps is 24-bit * 48 kHz * 2 channels,
        // even though the channel word itself no longer reaches the card.
        val line = assertNotNull(discordAudioQualityLine(format, catalogue("tr")))
        assertEquals(
            "🎧 Hi-Res Kayıpsız · FLAC · 2304 kbps · 24-bit · 48 kHz",
            line,
        )
        assertFalse(line.contains('\n'), "the card's third line is one line")
    }

    @Test
    fun `a hi-res card carries only the four measured facts after the badge`() {
        val format = DesktopStreamFormat(
            codec = "flac",
            sampleRateHz = 96_000,
            bitDepth = 24,
            channels = 2,
        )
        val card = assertNotNull(discordQualityCard(format, catalogue("en")))
        assertEquals("🎧 Hi-Res Lossless", card.badge)
        assertEquals("FLAC · 4608 kbps · 24-bit · 96 kHz", card.specs)
        assertEquals("🎧 Hi-Res Lossless · FLAC · 4608 kbps · 24-bit · 96 kHz", card.line)
    }

    @Test
    fun `cd-quality lossless is named Lossless rather than hi-res`() {
        val format = DesktopStreamFormat(
            codec = "audio/flac",
            sampleRateHz = 44_100,
            bitDepth = 16,
            channels = 2,
        )
        assertEquals(
            "🎧 Lossless · FLAC · 1411 kbps · 16-bit · 44.1 kHz",
            discordAudioQualityLine(format, catalogue("en")),
        )
    }

    @Test
    fun `an ordinary lossy stream gets its codec and bitrate without a badge`() {
        val format = DesktopStreamFormat(
            codec = "opus",
            kbps = 160,
            sampleRateHz = 48_000,
            channels = 2,
        )
        // The player's own badge is blank for a 160 kbps Opus, so the card does
        // not invent one; the measured facts still make the line useful.
        assertEquals("Opus · 160 kbps · 48 kHz", discordAudioQualityLine(format, catalogue("en")))
    }

    @Test
    fun `the top of lossy gets the player's High quality badge`() {
        val format = DesktopStreamFormat(
            codec = "aac",
            kbps = 320,
            sampleRateHz = 44_100,
            channels = 2,
        )
        assertEquals(
            "🎧 High quality · AAC · 320 kbps · 44.1 kHz",
            discordAudioQualityLine(format, catalogue("en")),
        )
    }

    @Test
    fun `Dolby Atmos is called out as such and not also as lossless`() {
        val format = DesktopStreamFormat(codec = "eac3-joc", kbps = 768, sampleRateHz = 48_000, channels = 6)
        assertEquals(
            "🎧 Dolby Atmos · E-AC-3 · 768 kbps · 48 kHz",
            discordAudioQualityLine(format, catalogue("en")),
        )
    }

    @Test
    fun `no channel layout reaches the card, whatever was measured`() {
        // The player's own format summary keeps "Stereo"/"Mono"; only the
        // Discord line drops it, and it must drop every form of it.
        listOf(1, 2, 6).forEach { channels ->
            val format = DesktopStreamFormat(
                codec = "flac",
                sampleRateHz = 48_000,
                bitDepth = 24,
                channels = channels,
            )
            val line = assertNotNull(discordAudioQualityLine(format, catalogue("en")))
            assertFalse(
                Regex("""\b(Stereo|Mono|channels?|ch)\b""").containsMatchIn(line),
                "$channels channels leaked into '$line'",
            )
        }
    }

    @Test
    fun `a field nothing measured is left out rather than printed as a placeholder`() {
        // Bit depth and channel count are unknown, so the computed lossless
        // bitrate is unknown too — only the rate the decoder did report stays.
        val format = DesktopStreamFormat(codec = "flac", sampleRateHz = 48_000)
        assertEquals("🎧 Lossless · FLAC · 48 kHz", discordAudioQualityLine(format, catalogue("en")))

        // The first field unknown as well: the line starts with what is there,
        // not with an empty segment's separator.
        val nameless = DesktopStreamFormat(kbps = 160, sampleRateHz = 44_100)
        assertEquals("160 kbps · 44.1 kHz", discordAudioQualityLine(nameless, catalogue("en")))
    }

    @Test
    fun `a format nothing has measured yet earns no line`() {
        assertNull(discordAudioQualityLine(null, catalogue("en")))
        assertNull(discordAudioQualityLine(DesktopStreamFormat(), catalogue("en")))
        assertNull(discordQualityBadge(DesktopStreamFormat()))
    }

    @Test
    fun `the line never runs past Discord's 128-character field`() {
        val format = DesktopStreamFormat(
            codec = "x".repeat(200),
            sampleRateHz = 96_000,
            bitDepth = 24,
            channels = 2,
        )
        val line = assertNotNull(discordAudioQualityLine(format, catalogue("en")))
        assertTrue(line.length <= 128, "${line.length} characters")
        assertFalse(line.endsWith(" "), "a truncation that leaves a trailing space reads as a typo")
    }

    @Test
    fun `the album keeps the third line until the quality switch is turned on`() {
        val lossy = DesktopStreamFormat(codec = "aac", kbps = 256, channels = 2)
        assertEquals("An Album", discordThirdLine(lossy, showQuality = false, album = "An Album"))
        assertEquals(
            "🎧 High quality · AAC · 256 kbps",
            discordThirdLine(lossy, showQuality = true, album = "An Album", catalogue("en")),
        )
        // Switched on but not measured yet: still the album, never an invented line.
        assertEquals("An Album", discordThirdLine(null, showQuality = true, album = "An Album", catalogue("en")))
    }

    // ── What the local socket is told ────────────────────────────────────

    @Test
    fun `the local activity always carries a name, custom or the app's own`() {
        val fallback = presence(settings = DiscordCardSettings(activityName = "")).ipcActivity()
        assertEquals("nextrobytemusic", fallback["name"]?.jsonPrimitive?.content)

        val custom = presence(settings = DiscordCardSettings(activityName = "My Deck")).ipcActivity()
        assertEquals("My Deck", custom["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `lead with the song names the details line, and only then`() {
        val artistBold = presence(settings = DiscordCardSettings(useDetails = false)).ipcActivity()
        assertEquals(1, artistBold["status_display_type"]?.jsonPrimitive?.int)

        val songBold = presence(settings = DiscordCardSettings(useDetails = true)).ipcActivity()
        assertEquals(2, songBold["status_display_type"]?.jsonPrimitive?.int)
    }

    @Test
    fun `the third line reaches the socket as large text`() {
        val format = DesktopStreamFormat(
            codec = "flac",
            sampleRateHz = 48_000,
            bitDepth = 24,
            channels = 2,
        )
        val on = presence(format = format, settings = DiscordCardSettings(showAudioQuality = true)).ipcActivity()
        val largeText = on["assets"]?.jsonObject?.get("large_text")?.jsonPrimitive?.content
        assertEquals("🎧 Hi-Res Lossless · FLAC · 2304 kbps · 24-bit · 48 kHz", largeText)
        assertFalse(largeText.orEmpty().contains('\n'), "the card's third line is one line")
        assertFalse(largeText.orEmpty().contains("Stereo"), "the channel word stays off the card")

        val off = presence(format = format, settings = DiscordCardSettings(showAudioQuality = false)).ipcActivity()
        assertEquals(
            "An Album",
            off["assets"]?.jsonObject?.get("large_text")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `the same Turkish line the player badge shows is what the socket receives`() {
        val format = DesktopStreamFormat(
            codec = "flac",
            sampleRateHz = 48_000,
            bitDepth = 24,
            channels = 2,
        )
        val activity = presence(
            format = format,
            settings = DiscordCardSettings(showAudioQuality = true),
            label = catalogue("tr"),
        ).ipcActivity()
        assertEquals(
            "🎧 Hi-Res Kayıpsız · FLAC · 2304 kbps · 24-bit · 48 kHz",
            activity["assets"]?.jsonObject?.get("large_text")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `a card is rebuilt with the same key at a later moment`() {
        // The timestamps move on every rebuild; Discord counts the bar itself,
        // so they must not be what makes two pushes look different.
        assertEquals(presence(nowMs = 1_000_000L).key, presence(nowMs = 9_999_999L).key)
    }

    @Test
    fun `every card setting changes the key, so the push follows the switch`() {
        val base = presence().key
        listOf(
            DiscordCardSettings(useDetails = true),
            DiscordCardSettings(status = "idle"),
            DiscordCardSettings(activityType = "playing"),
            DiscordCardSettings(activityName = "My Deck"),
            DiscordCardSettings(button1Visible = true),
            DiscordCardSettings(button1Visible = true, button1Text = "Open {song_name}"),
            DiscordCardSettings(button2Visible = true),
        ).forEach { changed ->
            assertNotEquals(base, presence(settings = changed).key, changed.toString())
        }
    }

    @Test
    fun `a new measured format changes the key only while the quality line is on`() {
        val lossy = DesktopStreamFormat(codec = "opus", kbps = 160, channels = 2)
        val lossless = DesktopStreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 16, channels = 2)

        val off = DiscordCardSettings(showAudioQuality = false)
        assertEquals(
            presence(format = lossy, settings = off).key,
            presence(format = lossless, settings = off).key,
            "the album is the third line, so a format that arrives changes nothing Discord draws",
        )

        val on = DiscordCardSettings(showAudioQuality = true)
        assertNotEquals(
            presence(format = lossy, settings = on).key,
            presence(format = lossless, settings = on).key,
        )
    }

    @Test
    fun `the countdown divides the playback rate out of both instants`() {
        val now = 1_000_000L
        val card = discordPresence(
            song = song,
            positionMs = 30_000L,
            durationMs = 210_000L,
            format = null,
            speed = 1.5f,
            settings = DiscordCardSettings(),
            nowMs = now,
        )
        assertEquals(now - 20_000L, card.startMs)
        assertEquals(now + 120_000L, card.endMs)
        assertTrue("[1.50x]" in card.title)
    }

    @Test
    fun `a snapshot taken before the settings changed is advanced to now`() {
        // The UI last published this snapshot at position 10s, five seconds ago.
        val state = DesktopPlaybackState(
            song = song,
            isPlaying = true,
            positionMs = 10_000L,
            positionSampledAtNanos = 1_000_000_000L,
            durationMs = 210_000L,
        )
        assertEquals(15_000L, projectedPositionMs(state, speed = 1f, nowNanos = 6_000_000_000L))
        assertEquals(17_500L, projectedPositionMs(state, speed = 1.5f, nowNanos = 6_000_000_000L))
    }

    @Test
    fun `a paused snapshot stays where the listener stopped it`() {
        val paused = DesktopPlaybackState(
            song = song,
            isPlaying = false,
            positionMs = 10_000L,
            positionSampledAtNanos = 1_000_000_000L,
            durationMs = 210_000L,
        )
        assertEquals(10_000L, projectedPositionMs(paused, speed = 1f, nowNanos = 6_000_000_000L))
    }

    @Test
    fun `the projected playhead never runs past the end of the track`() {
        val state = DesktopPlaybackState(
            song = song,
            isPlaying = true,
            positionMs = 200_000L,
            positionSampledAtNanos = 1_000_000_000L,
            durationMs = 210_000L,
        )
        assertEquals(210_000L, projectedPositionMs(state, speed = 1f, nowNanos = 60_000_000_000L))
    }

    // ── When a push happens ──────────────────────────────────────────────

    @Test
    fun `the switch being off publishes nothing, even mid-track`() {
        val playing = DesktopPlaybackState(song = song, isPlaying = true, durationMs = 210_000L)
        assertEquals(
            DiscordPresenceAction.Idle,
            discordPresenceAction(enabled = false, state = playing, speed = 1f, settings = DiscordCardSettings(), nowMs = 0L),
        )
    }

    @Test
    fun `a stopped player clears the card`() {
        val paused = DesktopPlaybackState(song = song, isPlaying = false, durationMs = 210_000L)
        assertEquals(
            DiscordPresenceAction.Clear,
            discordPresenceAction(enabled = true, state = paused, speed = 1f, settings = DiscordCardSettings(), nowMs = 0L),
        )
        assertEquals(
            DiscordPresenceAction.Clear,
            discordPresenceAction(enabled = true, state = null, speed = 1f, settings = DiscordCardSettings(), nowMs = 0L),
        )
    }

    @Test
    fun `a playing track asks for its card`() {
        val playing = DesktopPlaybackState(song = song, isPlaying = true, durationMs = 210_000L)
        val action = discordPresenceAction(true, playing, 1f, DiscordCardSettings(), 1_000L)
        assertTrue(action is DiscordPresenceAction.Show)
        assertEquals("A Song", action.presence.title)
    }

    // ── The send cache ───────────────────────────────────────────────────

    @Test
    fun `a send that fails is not remembered, so the same card is tried again`() = runBlocking {
        var attempts = 0
        val cache = DiscordPresenceCache(
            send = { attempts++; attempts > 1 },
            clearTransport = {},
        )
        val card = presence()
        assertFalse(cache.push(card), "the first send fails")
        assertFalse(cache.showing)
        assertTrue(cache.push(card), "the same card must not be swallowed by the failed send")
        assertEquals(2, attempts)
        assertTrue(cache.showing)
    }

    @Test
    fun `the same card is sent once, and again after a new connection`() = runBlocking {
        var sent = 0
        val cache = DiscordPresenceCache(
            send = { sent++; true },
            clearTransport = {},
        )
        val card = presence()
        assertTrue(cache.push(card))
        assertFalse(cache.push(card), "an unchanged card costs no frame")
        assertEquals(1, sent)
        cache.forget()
        assertTrue(cache.push(card), "a connection that came back has been told nothing")
        assertEquals(2, sent)
    }

    @Test
    fun `a force asks for a fresh countdown even when the card is unchanged`() = runBlocking {
        var sent = 0
        val cache = DiscordPresenceCache(send = { sent++; true }, clearTransport = {})
        val card = presence()
        cache.push(card)
        cache.push(card)
        assertEquals(1, sent)
        cache.push(card, force = true)
        assertEquals(2, sent)
    }

    @Test
    fun `turning the presence off clears it once and forgets what was shown`() = runBlocking {
        var clears = 0
        val cache = DiscordPresenceCache(send = { true }, clearTransport = { clears++ })
        cache.push(presence())
        assertTrue(cache.showing)
        cache.clear()
        assertFalse(cache.showing)
        cache.clear()
        assertEquals(1, clears, "nothing of ours is up, so a second null costs Discord a frame for nothing")
    }
}
