package com.music.bitchord.data.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * YouTube Music answers the lyrics tab with two different payloads depending
 * on the client it is asked as: the web client gets a plain description shelf,
 * the phone gets `timedLyricsModel`. Both shapes are captured from the real
 * endpoint.
 */
class YouTubeMusicLyricsTest {

    private fun page(json: String) = Json.parseToJsonElement(json).jsonObject

    private val timed = page(
        """
        {
          "contents": {
            "elementRenderer": {
              "newElement": {
                "type": {
                  "componentType": {
                    "model": {
                      "timedLyricsModel": {
                        "lyricsData": {
                          "timedLyricsData": [
                            {
                              "lyricLine": "first line",
                              "cueRange": {
                                "startTimeMilliseconds": "2210",
                                "endTimeMilliseconds": "7440"
                              }
                            },
                            {
                              "lyricLine": "second line",
                              "cueRange": {
                                "startTimeMilliseconds": "7440",
                                "endTimeMilliseconds": "9000"
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
        """.trimIndent(),
    )

    private val plain = page(
        """
        {
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicDescriptionShelfRenderer": {
                    "description": {
                      "runs": [
                        { "text": "first line\nsecond line" }
                      ]
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `reads the timed lyrics the mobile client is served`() {
        val lines = timedLyrics(timed)!!
        assertEquals(2, lines.size)
        assertEquals(2210L, lines[0].timeMs)
        assertEquals("first line", lines[0].text)
        assertEquals(7440L, lines[0].sungUntilMs)
        assertEquals(7440L, lines[1].timeMs)
    }

    @Test
    fun `falls back to the plain shelf when the track has no timed lyrics`() {
        assertNull(timedLyrics(plain))
        val lines = plainLyrics(plain)!!
        assertEquals(listOf("first line", "second line"), lines.map { it.text })
    }
}
