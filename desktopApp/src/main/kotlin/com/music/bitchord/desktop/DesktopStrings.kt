package com.music.bitchord.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's own copy, in whichever language is in force.
 *
 * The catalogues are Android's own, copied in at build time from its `res` values folders,
 * so a translation written once is read by both targets and desktop never drifts into its own
 * wording for a string Android already has.
 *
 * A key with no translation in the chosen language falls back to English rather than showing the
 * key: a half-translated locale should read as mostly-English, not as a debug build.
 */
object DesktopStrings {

    /**
     * How a `\uXXXX` escape in a catalogue is read back.
     *
     * Declared above everything that can load a catalogue, and not at the bottom where it used to
     * sit. Kotlin initializes an object's properties top to bottom, so while the first catalogue
     * was loaded from an `init` block that ran before this field existed, every call to `load`
     * threw a null-pointer error inside its `runCatching`, came back as an empty map, and the
     * whole first window fell through to the English catalogues. The picker's `setLanguage`
     * reloaded the catalogue later, once this field existed, which is why switching English →
     * Turkish "fixed" the language until the next launch. Anything the loader touches has to be
     * initialized before the loader's first use.
     */
    private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")

    /** The languages there are catalogues for, by IETF tag. */
    val available: List<DesktopLanguage> = listOf(
        DesktopLanguage("", "System default"),
        DesktopLanguage("en", "English"),
        DesktopLanguage("de", "Deutsch"),
        DesktopLanguage("es", "Español"),
        DesktopLanguage("fr", "Français"),
        DesktopLanguage("he", "עברית"),
        DesktopLanguage("hi", "हिन्दी"),
        DesktopLanguage("id", "Bahasa Indonesia"),
        DesktopLanguage("it", "Italiano"),
        DesktopLanguage("ja", "日本語"),
        DesktopLanguage("pl", "Polski"),
        DesktopLanguage("pt", "Português"),
        DesktopLanguage("ru", "Русский"),
        DesktopLanguage("tr", "Türkçe"),
        DesktopLanguage("zh-Hans", "简体中文"),
        DesktopLanguage("zh-Hant", "繁體中文"),
    )

    private val english: Map<String, String> by lazy { catalogue("en") }

    private val _language = MutableStateFlow(DesktopPersistence().string(KEY_LANGUAGE))

    /** The stored choice: a tag, or blank for the system's own language. */
    val language: StateFlow<String> = _language

    /**
     * The strings in force, loaded while this object is created and replaced by [setLanguage].
     *
     * A property initializer rather than an `init` block, so the map the first composition reads
     * is assigned in the same declaration order that initializes [available] and [_language]
     * above it. An initializer that runs before its own dependencies is how the first frame came
     * out English while the settings screen said the chosen language was Turkish.
     */
    @Volatile
    private var current: Map<String, String> = catalogue(resolvedTag())

    /** Which catalogue is actually in use, after the system default is resolved. */
    fun resolvedTag(): String = resolveTag(_language.value, Locale.getDefault().toLanguageTag())

    /**
     * [chosen] as the tag of a catalogue this build ships, with [systemTag] answering a blank
     * choice.
     *
     * The stored value is only a hint until a catalogue exists for it. It can arrive as "tr",
     * "TR", "tr-TR", "zh-TW", a legacy bare "zh", or a language a later build dropped, and the
     * file name must not be built out of whichever case or region happened to be stored:
     * `catalogue("TR")` would look for `TR.xml`, find nothing, and leave the UI in English. Each
     * value resolves to the catalogue's own spelling, and one with no catalogue lands on English
     * — a complete language rather than keys from whichever catalogues did load.
     */
    internal fun resolveTag(chosen: String, systemTag: String): String {
        // `Locale.forLanguageTag` throws on a malformed tag ("!!", "en-"), and a stored value is
        // whatever a previous build or a hand-edited preference left there. A value this code
        // cannot parse is a value there is no catalogue for, so it takes the same English
        // fallback rather than failing the object that the first composition is waiting on.
        val normalized = runCatching {
            when {
                chosen.isBlank() -> com.music.bitchord.data.LocaleTags.normalizeAppTag(systemTag)
                chosen.trim().equals("zh", ignoreCase = true) ->
                    com.music.bitchord.data.LocaleTags.ZH_HANS
                else -> com.music.bitchord.data.LocaleTags.normalizeAppTag(chosen)
            }
        }.getOrDefault("en")
        return available
            .firstOrNull { it.tag.isNotBlank() && it.tag.equals(normalized, ignoreCase = true) }
            ?.tag
            ?: "en"
    }

    /**
     * One language, both layers.
     *
     * `<tag>.xml` is Android's own catalogue, copied in at build time. `desktop-<tag>.xml` holds
     * the copy only this target has — its window chrome, the source editor, output precision — and
     * is laid over the top, so a desktop key never collides with an Android one.
     *
     * zh-Hant/zh-Hans fall back to legacy "zh" files so old installs keep
     * working while the script-tagged catalogues roll out.
     */
    private fun catalogue(tag: String): Map<String, String> = when (tag) {
        "zh-Hant" ->
            load("zh-Hant").ifEmpty { load("zh") } +
                load("desktop-zh-Hant").ifEmpty { load("desktop-zh") }
        "zh-Hans" ->
            load("zh-Hans").ifEmpty { load("zh") } +
                load("desktop-zh-Hans").ifEmpty { load("desktop-zh") }
        else -> load(tag) + load("desktop-$tag")
    }

    fun setLanguage(tag: String) {
        DesktopPersistence().saveString(KEY_LANGUAGE, tag)
        _language.value = tag
        current = catalogue(resolvedTag())
    }

    /**
     * The label shown for the stored choice, named by the catalogue actually in force.
     *
     * A blank choice is the picker's "System default". A stored tag this build has no catalogue
     * for is labelled as the language the UI really runs in (English), rather than as "System
     * default", which would claim a setting that is not in effect.
     */
    fun languageLabel(): String {
        if (_language.value.isBlank()) return "System default"
        return available.firstOrNull { it.tag == resolvedTag() }?.label ?: "English"
    }

    /**
     * The string for [key], or [fallback] when no catalogue has one.
     *
     * The fallback is the English text the call site would otherwise have held literally, so an
     * untranslated key still reads as the sentence it was written as.
     */
    operator fun get(key: String, fallback: String = key): String =
        current[key] ?: english[key] ?: fallback

    /** As [get], with Android's `%1$s`-style positional arguments filled in. */
    fun format(key: String, vararg arguments: Any?, fallback: String = key): String =
        runCatching { String.format(get(key, fallback), *arguments) }.getOrDefault(get(key, fallback))

    private fun load(tag: String): Map<String, String> = runCatching {
        val stream = DesktopStrings::class.java.getResourceAsStream("/strings/$tag.xml")
            ?: return@runCatching emptyMap()
        val document = stream.use {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(it)
        }
        val nodes = document.getElementsByTagName("string")
        buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                put(name, unescape(node.textContent.orEmpty()))
            }
        }
    }.getOrDefault(emptyMap())

    /**
     * A catalogue value as the UI should read it.
     *
     * Android escapes an apostrophe for its own resource parser, and a `\uXXXX` can survive a
     * round trip out of source into a catalogue — either of which would otherwise be drawn
     * literally, which is how "Don't" once reached the screen as "Don\u2019t".
     */
    internal fun unescape(raw: String): String {
        val decoded = UNICODE_ESCAPE.replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
        return decoded.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
    }

    internal const val KEY_LANGUAGE = "app_language"
}

/** One entry in the language picker. */
data class DesktopLanguage(val tag: String, val label: String)
