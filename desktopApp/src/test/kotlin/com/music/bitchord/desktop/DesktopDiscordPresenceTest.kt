package com.music.bitchord.desktop

import com.music.bitchord.data.QualityBadge
import com.music.bitchord.data.QualityBadgeState
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop Discord card: the defaults a fresh profile gets, the quality line
 * the audio-quality switch adds, and the rules that decide when Discord is told
 * anything at all.
 *
 * No socket and no account are involved. The local socket's framing has its own
 * test; what is pinned here is the part that used to go wrong silently — a card
 * that was never sent because a failed send had already been recorded, and
 * settings that only reached Discord at the next track.
 *
 * The labels themselves are the shared player decision. The tests take both
 * catalogues from their shipped files — the desktop's `strings` XML and the
 * player's compose-resource `.cvr` in the sharedUi jar — instead of switching
 * [DesktopStrings]' language, because the language in force belongs to whoever
 * runs the tests.
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
        quality: QualityBadgeState? = null,
        settings: DiscordCardSettings = DiscordCardSettings(),
        nowMs: Long = 1_000_000L,
        song: Song = this.song,
        label: (QualityBadge) -> String = { badge -> qualityBadgeLabel(badge) },
    ) = discordPresence(
        song = song,
        positionMs = 30_000L,
        durationMs = 210_000L,
        format = format,
        quality = quality,
        speed = 1f,
        settings = settings,
        nowMs = nowMs,
        label = label,
    )

    /**
     * The badge inputs for [format] as the player would see them, with the
     * desktop's own pinned rung by default.
     */
    private fun qualityFor(
        format: DesktopStreamFormat?,
        isLoading: Boolean = false,
        stillRacing: Boolean = false,
        losslessRequested: Boolean = true,
        effectiveQuality: AudioQuality = AudioQuality.LOSSLESS,
    ) = QualityBadgeState(
        isLoading = isLoading,
        stillRacing = stillRacing,
        losslessRequested = losslessRequested,
        effectiveQuality = effectiveQuality,
        nerdStats = format?.toNerdStats(null),
    )

    /** The card's line with the wording from one desktop catalogue. */
    private fun lineFor(
        format: DesktopStreamFormat?,
        quality: QualityBadgeState?,
        tag: String,
    ): String? = discordAudioQualityLine(format, quality) { badge ->
        qualityBadgeLabel(badge, desktopLabel(tag))
    }

    /** One desktop catalogue's strings, read from its resource. */
    private fun desktopStrings(tag: String): Map<String, String> {
        val stream = DesktopStrings::class.java.getResourceAsStream("/strings/$tag.xml")
        assertNotNull(stream, "no catalogue at /strings/$tag.xml")
        return stream.use { input ->
            Regex("""<string name="([^"]+)">([^<]*)</string>""")
                .findAll(input.readBytes().decodeToString())
                .associate { it.groupValues[1] to DesktopStrings.unescape(it.groupValues[2]) }
        }
    }

    private fun desktopLabel(tag: String): (String, String) -> String {
        val strings = desktopStrings(tag)
        return { key, fallback -> strings[key] ?: fallback }
    }

    /**
     * The player's own catalogue, out of the sharedUi jar.
     *
     * Compose packs each language into a `.cvr` file of `string|key|base64`
     * lines. Reading it here compares the card's words with the words the
     * player's badge actually resolves, without a Compose environment or the
     * language in force.
     */
    private fun playerStrings(tag: String): Map<String, String> {
        val folder = if (tag == "en") "values" else "values-$tag"
        val path = "/composeResources/com.music.bitchord.sharedui.resources/$folder/strings.commonMain.cvr"
        val stream = DesktopStrings::class.java.getResourceAsStream(path)
        assertNotNull(stream, "no player catalogue at $path")
        return stream.use { input ->
            input.readBytes().decodeToString().lineSequence()
                .filter { it.startsWith("string|") }
                .mapNotNull { line ->
                    val parts = line.split('|', limit = 3)
                    if (parts.size != 3) {
                        null
                    } else {
                        parts[1] to String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8)
                    }
                }
                .toMap()
        }
    }

    /** The catalogue key the player's badge uses for [badge]. */
    private fun playerKey(badge: QualityBadge): String = when (badge) {
        QualityBadge.UPGRADING_HI_QUALITY -> "high_quality_upgrading"
        QualityBadge.UPGRADING -> "upgrading_quality"
        QualityBadge.HI_RES_LOSSLESS -> "hi_res_lossless"
        QualityBadge.LOSSLESS -> "lossless"
        QualityBadge.DOLBY_ATMOS -> "dolby_atmos"
        QualityBadge.HIGH_QUALITY -> "high_quality"
        QualityBadge.DATA_SAVER -> "data_saver"
        QualityBadge.MEDIUM_QUALITY -> "medium_quality"
    }

    private val aac320 = DesktopStreamFormat(codec = "aac", kbps = 320, sampleRateHz = 44_100, channels = 2)
    private val aac160 = DesktopStreamFormat(codec = "aac", kbps = 160, sampleRateHz = 48_000, channels = 2)
    private val opus160 = DesktopStreamFormat(codec = "opus", kbps = 160, sampleRateHz = 48_000, channels = 2)
    private val opus64 = DesktopStreamFormat(codec = "opus", kbps = 64, sampleRateHz = 48_000, channels = 2)
    private val flacHiRes = DesktopStreamFormat(codec = "flac", sampleRateHz = 96_000, bitDepth = 24, channels = 2)
    private val flacCd = DesktopStreamFormat(codec = "audio/flac", sampleRateHz = 44_100, bitDepth = 16, channels = 2)
    private val atmos = DesktopStreamFormat(codec = "eac3-joc", kbps = 768, sampleRateHz = 48_000, channels = 6)

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

    // ── One test per player badge state ──────────────────────────────────

    @Test
    fun `an unmeasured lossless request shows the player's upgrading label`() {
        val quality = qualityFor(format = null, isLoading = true)
        assertEquals(QualityBadge.UPGRADING, quality.badge)
        assertEquals("🎧 Kalite yükseltiliyor", lineFor(null, quality, "tr"))
        assertEquals("🎧 Upgrading quality", lineFor(null, quality, "en"))
    }

    @Test
    fun `a lossless race over a 320k stream shows high quality upgrading`() {
        val quality = qualityFor(aac320, stillRacing = true)
        assertEquals(QualityBadge.UPGRADING_HI_QUALITY, quality.badge)
        assertEquals(
            "🎧 Yüksek kalite, yükseltiliyor · AAC · 320 kbps · 44.1 kHz",
            lineFor(aac320, quality, "tr"),
        )
        assertEquals(
            "🎧 High quality, upgrading · AAC · 320 kbps · 44.1 kHz",
            lineFor(aac320, quality, "en"),
        )
    }

    @Test
    fun `hi-res lossless carries the player badge and the measured fields`() {
        val quality = qualityFor(flacHiRes)
        assertEquals(QualityBadge.HI_RES_LOSSLESS, quality.badge)
        assertEquals(
            "🎧 Hi-Res Kayıpsız · FLAC · 4608 kbps · 24-bit · 96 kHz",
            lineFor(flacHiRes, quality, "tr"),
        )
        assertEquals(
            "🎧 Hi-Res Lossless · FLAC · 4608 kbps · 24-bit · 96 kHz",
            lineFor(flacHiRes, quality, "en"),
        )
    }

    @Test
    fun `cd-quality lossless is named Lossless rather than hi-res`() {
        val quality = qualityFor(flacCd)
        assertEquals(QualityBadge.LOSSLESS, quality.badge)
        assertEquals(
            "🎧 Kayıpsız · FLAC · 1411 kbps · 16-bit · 44.1 kHz",
            lineFor(flacCd, quality, "tr"),
        )
        assertEquals(
            "🎧 Lossless · FLAC · 1411 kbps · 16-bit · 44.1 kHz",
            lineFor(flacCd, quality, "en"),
        )
    }

    @Test
    fun `Dolby Atmos is called out as such and not also as lossless`() {
        val quality = qualityFor(atmos)
        assertEquals(QualityBadge.DOLBY_ATMOS, quality.badge)
        assertEquals("🎧 Dolby Atmos · E-AC-3 · 768 kbps · 48 kHz", lineFor(atmos, quality, "tr"))
        assertEquals("🎧 Dolby Atmos · E-AC-3 · 768 kbps · 48 kHz", lineFor(atmos, quality, "en"))
    }

    @Test
    fun `a 320k lossy track shows the player's high quality label`() {
        val quality = qualityFor(aac320)
        assertEquals(QualityBadge.HIGH_QUALITY, quality.badge)
        // The request: whatever the player badge says is what the card says.
        assertEquals(
            "🎧 Yüksek kalite · AAC · 320 kbps · 44.1 kHz",
            lineFor(aac320, quality, "tr"),
        )
        assertEquals(
            "🎧 High quality · AAC · 320 kbps · 44.1 kHz",
            lineFor(aac320, quality, "en"),
        )
    }

    @Test
    fun `the low rung's Data-Saver label is shown exactly when the player shows it`() {
        val quality = qualityFor(opus64, effectiveQuality = AudioQuality.LOW)
        assertEquals(QualityBadge.DATA_SAVER, quality.badge)
        assertEquals("🎧 Data-Saver · Opus · 64 kbps · 48 kHz", lineFor(opus64, quality, "tr"))
        assertEquals("🎧 Data-Saver · Opus · 64 kbps · 48 kHz", lineFor(opus64, quality, "en"))
    }

    @Test
    fun `the medium rung's Medium Quality label is shown exactly when the player shows it`() {
        val quality = qualityFor(aac160, effectiveQuality = AudioQuality.MEDIUM)
        assertEquals(QualityBadge.MEDIUM_QUALITY, quality.badge)
        assertEquals("🎧 Medium Quality · AAC · 160 kbps · 48 kHz", lineFor(aac160, quality, "tr"))
        assertEquals("🎧 Medium Quality · AAC · 160 kbps · 48 kHz", lineFor(aac160, quality, "en"))
    }

    @Test
    fun `an ordinary lossy stream gets its codec and bitrate without a badge`() {
        val quality = qualityFor(opus160)
        // The player's own badge is blank for 160 kbps Opus on the lossless
        // rung, so the card does not invent one; the measured facts still make
        // the line useful.
        assertNull(quality.badge)
        assertEquals("Opus · 160 kbps · 48 kHz", lineFor(opus160, quality, "tr"))
        assertEquals("Opus · 160 kbps · 48 kHz", lineFor(opus160, quality, "en"))
    }

    @Test
    fun `the card's badge is the player's label for the same decided state`() {
        val cases = listOf(
            null to qualityFor(null, isLoading = true),
            aac320 to qualityFor(aac320, isLoading = true),
            flacHiRes to qualityFor(flacHiRes),
            flacCd to qualityFor(flacCd),
            atmos to qualityFor(atmos),
            aac320 to qualityFor(aac320),
            opus64 to qualityFor(opus64, effectiveQuality = AudioQuality.LOW),
            aac160 to qualityFor(aac160, effectiveQuality = AudioQuality.MEDIUM),
        )
        listOf("tr", "en").forEach { tag ->
            val player = if (tag == "en") playerStrings("en") else playerStrings("en") + playerStrings(tag)
            val desktop = if (tag == "en") desktopStrings("en") else desktopStrings("en") + desktopStrings(tag)
            cases.forEach { (format, quality) ->
                val badge = assertNotNull(quality.badge, "$tag ${quality.effectiveQuality}")
                // The player's Dolby badge is the one label it still holds as a
                // literal rather than a catalogue entry.
                val playerText = if (badge == QualityBadge.DOLBY_ATMOS) {
                    "Dolby Atmos"
                } else {
                    assertNotNull(player[playerKey(badge)], "$tag player has no ${playerKey(badge)}")
                }
                val card = assertNotNull(
                    discordQualityCard(format, quality) { chosen ->
                        qualityBadgeLabel(chosen) { key, fallback -> desktop[key] ?: fallback }
                    },
                )
                assertEquals(
                    "🎧 $playerText",
                    card.badge,
                    "$tag ${badge.name} must read as the player's badge",
                )
            }
        }
    }

    @Test
    fun `the desktop catalogue words every badge exactly as the player's catalogue does`() {
        val keys = listOf(
            "high_quality_upgrading",
            "upgrading_quality",
            "hi_res_lossless",
            "lossless",
            "high_quality",
            "data_saver",
            "medium_quality",
        )
        listOf("tr", "en").forEach { tag ->
            val player = if (tag == "en") playerStrings("en") else playerStrings("en") + playerStrings(tag)
            val desktop = if (tag == "en") desktopStrings("en") else desktopStrings("en") + desktopStrings(tag)
            keys.forEach { key ->
                assertEquals(desktop[key], player[key], "$tag/$key differs from the player's word")
            }
            // The player's Dolby label is a literal; the desktop has a key that
            // resolves to the same text everywhere.
            assertEquals("Dolby Atmos", desktop["dolby_atmos"], "$tag Dolby")
        }
    }

    // ── The line's shape ─────────────────────────────────────────────────

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
            val line = assertNotNull(lineFor(format, qualityFor(format), "en"))
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
        assertEquals(
            "🎧 Lossless · FLAC · 48 kHz",
            lineFor(format, qualityFor(format), "en"),
        )

        // The first field unknown as well: the line starts with what is there,
        // not with an empty segment's separator.
        val nameless = DesktopStreamFormat(kbps = 160, sampleRateHz = 44_100)
        assertEquals("160 kbps · 44.1 kHz", lineFor(nameless, qualityFor(nameless), "en"))
    }

    @Test
    fun `a format nothing has measured yet earns no line`() {
        assertNull(discordAudioQualityLine(null, null))
        assertNull(discordAudioQualityLine(DesktopStreamFormat(), qualityFor(DesktopStreamFormat())))
        assertNull(qualityFor(DesktopStreamFormat()).badge)
    }

    @Test
    fun `the line never runs past Discord's 128-character field`() {
        val format = DesktopStreamFormat(
            codec = "x".repeat(200),
            sampleRateHz = 96_000,
            bitDepth = 24,
            channels = 2,
        )
        val line = assertNotNull(discordAudioQualityLine(format, qualityFor(format)))
        assertTrue(line.length <= 128, "${line.length} characters")
        assertFalse(line.endsWith(" "), "a truncation that leaves a trailing space reads as a typo")
    }

    @Test
    fun `the card is one line at most`() {
        listOf(flacHiRes, aac320, atmos).forEach { format ->
            val line = assertNotNull(lineFor(format, qualityFor(format), "tr"))
            assertFalse(line.contains('\n'), line)
        }
    }

    @Test
    fun `the album keeps the third line until the quality switch is turned on`() {
        val quality = qualityFor(aac160.copy(kbps = 256))
        assertEquals(
            "An Album",
            discordThirdLine(aac160.copy(kbps = 256), quality, showQuality = false, album = "An Album"),
        )
        assertEquals(
            "🎧 Yüksek kalite · AAC · 256 kbps · 48 kHz",
            discordThirdLine(
                aac160.copy(kbps = 256),
                quality,
                showQuality = true,
                album = "An Album",
                label = { badge -> qualityBadgeLabel(badge, desktopLabel("tr")) },
            ),
        )
        // Switched on but not measured yet: still the album, never an invented line.
        assertEquals("An Album", discordThirdLine(null, null, showQuality = true, album = "An Album"))
    }

    // ── The desktop's own inputs ─────────────────────────────────────────

    @Test
    fun `the desktop state is the player's own, with the pinned quality flows`() {
        val state = DesktopPlaybackState(
            song = song,
            isPlaying = true,
            isLoading = true,
            streamFormat = aac320,
            searchingBetter = false,
        )
        val quality = desktopQualityBadgeState(state)
        // DesktopPlayerSettings pins both shared quality flows to LOSSLESS, so
        // the player badge (and therefore the card) always treats a lossless
        // request as in flight.
        assertEquals(AudioQuality.LOSSLESS, quality.effectiveQuality)
        assertTrue(quality.losslessRequested)
        // Loading with a measurement in hand keeps the measured label; only a
        // race, or loading before anything is measured, says upgrading.
        assertEquals(QualityBadge.HIGH_QUALITY, quality.badge)
        assertEquals("audio/aac", quality.nerdStats?.mimeType)
        assertEquals(320, quality.nerdStats?.bitrateKbps)

        // searchingBetter is the window's source for NerdStats.racingLossless.
        val racing = state.copy(isLoading = false, searchingBetter = true)
        assertEquals(QualityBadge.UPGRADING_HI_QUALITY, desktopQualityBadgeState(racing).badge)
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
    fun `the high quality line reaches the socket as large text`() {
        val quality = qualityFor(aac320)
        val on = presence(
            format = aac320,
            quality = quality,
            settings = DiscordCardSettings(showAudioQuality = true),
            label = { badge -> qualityBadgeLabel(badge, desktopLabel("tr")) },
        ).ipcActivity()
        val largeText = on["assets"]?.jsonObject?.get("large_text")?.jsonPrimitive?.content
        assertEquals("🎧 Yüksek kalite · AAC · 320 kbps · 44.1 kHz", largeText)
        assertFalse(largeText.orEmpty().contains('\n'), "the card's third line is one line")
        assertFalse(largeText.orEmpty().contains("Stereo"), "the channel word stays off the card")

        val off = presence(
            format = aac320,
            quality = quality,
            settings = DiscordCardSettings(showAudioQuality = false),
            label = { badge -> qualityBadgeLabel(badge, desktopLabel("tr")) },
        ).ipcActivity()
        assertEquals(
            "An Album",
            off["assets"]?.jsonObject?.get("large_text")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `the hi-res line reaches the socket in the language of the catalogue`() {
        val quality = qualityFor(flacHiRes)
        val english = presence(
            format = flacHiRes,
            quality = quality,
            settings = DiscordCardSettings(showAudioQuality = true),
            label = { badge -> qualityBadgeLabel(badge, desktopLabel("en")) },
        ).ipcActivity()
        assertEquals(
            "🎧 Hi-Res Lossless · FLAC · 4608 kbps · 24-bit · 96 kHz",
            english["assets"]?.jsonObject?.get("large_text")?.jsonPrimitive?.content,
        )

        val turkish = presence(
            format = flacHiRes,
            quality = quality,
            settings = DiscordCardSettings(showAudioQuality = true),
            label = { badge -> qualityBadgeLabel(badge, desktopLabel("tr")) },
        ).ipcActivity()
        assertEquals(
            "🎧 Hi-Res Kayıpsız · FLAC · 4608 kbps · 24-bit · 96 kHz",
            turkish["assets"]?.jsonObject?.get("large_text")?.jsonPrimitive?.content,
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
        val lossyQuality = qualityFor(opus160)
        val losslessQuality = qualityFor(flacCd)

        val off = DiscordCardSettings(showAudioQuality = false)
        assertEquals(
            presence(format = opus160, quality = lossyQuality, settings = off).key,
            presence(format = flacCd, quality = losslessQuality, settings = off).key,
            "the album is the third line, so a format that arrives changes nothing Discord draws",
        )

        val on = DiscordCardSettings(showAudioQuality = true)
        assertNotEquals(
            presence(format = opus160, quality = lossyQuality, settings = on).key,
            presence(format = flacCd, quality = losslessQuality, settings = on).key,
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
            quality = null,
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
