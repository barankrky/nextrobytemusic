package com.music.bitchord.desktop

import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The catalogue the first composition reads.
 *
 * `DesktopStrings` is an object, so a test JVM initializes it once and every later test in the
 * same worker sees the language the previous one left behind. That cannot reproduce a defect in
 * the object's own initialization order, which is exactly where the startup-language bug lived:
 * the initial catalogue was loaded before one of the fields the loader uses, the loader's
 * `runCatching` swallowed the resulting error into an empty map, and every lookup on the first
 * frame fell through to English until `setLanguage` reloaded it.
 *
 * These tests therefore initialize a fresh copy of the class in an isolated loader, with the
 * stored preference set first. Whatever that copy answers is what a cold launch would draw.
 */
class DesktopStringsStartupTest {

    @Test
    fun `the first catalogue read after launch is in the stored language`() {
        val persistence = DesktopPersistence()
        val before = persistence.string(DesktopStrings.KEY_LANGUAGE)
        try {
            persistence.saveString(DesktopStrings.KEY_LANGUAGE, "tr")
            assertEquals(
                "Ayarlar",
                firstLookup("settings", "Settings"),
                "the first catalogue load ignored the stored language",
            )
            assertEquals(
                "Uygulama dili",
                firstLookup("app_language", "App language"),
                "the first catalogue load ignored the stored language",
            )
            // The Discord card's quality line goes through qualityBadgeLabel
            // (DesktopDiscordAudioQuality.kt:74 → DesktopQualityBadge.kt:78 → DesktopStrings),
            // so this is the exact key its first publish reads.
            assertEquals(
                "Hi-Res Kayıpsız",
                firstLookup("hi_res_lossless", "Hi-Res Lossless"),
                "the first Discord card would go out in English",
            )
            assertEquals("tr", firstResolvedTag())
        } finally {
            persistence.saveString(DesktopStrings.KEY_LANGUAGE, before)
        }
    }

    @Test
    fun `a stored language with no catalogue still reads as one complete language`() {
        val persistence = DesktopPersistence()
        val before = persistence.string(DesktopStrings.KEY_LANGUAGE)
        try {
            persistence.saveString(DesktopStrings.KEY_LANGUAGE, "xx-YY")
            assertEquals("en", firstResolvedTag())
            assertEquals("Settings", firstLookup("settings", "Settings"))
            assertEquals("App language", firstLookup("app_language", "App language"))
        } finally {
            persistence.saveString(DesktopStrings.KEY_LANGUAGE, before)
        }
    }

    private fun firstLookup(key: String, fallback: String): String {
        val (type, instance) = freshStrings()
        return type.getMethod("get", String::class.java, String::class.java)
            .invoke(instance, key, fallback) as String
    }

    private fun firstResolvedTag(): String {
        val (type, instance) = freshStrings()
        return type.getMethod("resolvedTag").invoke(instance) as String
    }

    /**
     * A copy of [DesktopStrings] that has not been initialized in this JVM yet.
     *
     * The parent is the platform loader rather than the test's own, so the application classes
     * (and their Kotlin state) are loaded again instead of being shared with the object the rest
     * of the suite has already touched. `java.util.prefs` belongs to the platform, so the copy
     * still reads the same stored preference the real app reads.
     */
    private fun freshStrings(): Pair<Class<*>, Any> {
        val urls = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter(String::isNotBlank)
            .map { File(it).toURI().toURL() }
            .toTypedArray()
        val loader = URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
        val type = loader.loadClass("com.music.bitchord.desktop.DesktopStrings")
        return type to type.getField("INSTANCE").get(null)
    }
}
