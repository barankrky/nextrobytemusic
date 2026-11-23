package com.music.bitchord

import com.music.bitchord.ui.components.SUPPORTED_LANGUAGES
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every language the app offers must translate every key in values/strings.xml. A missing key
 * doesn't crash, it silently falls back to English mid-screen, so this catches it at test time.
 */
@RunWith(Parameterized::class)
class LocaleStringsTest(private val tag: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun locales(): List<String> = SUPPORTED_LANGUAGES.map { it.tag }.filter { it != "en" }

        private val resDir: File by lazy {
            val projectRoot = File(".").canonicalFile
            if (File(projectRoot, "app/src/main/res").exists()) {
                File(projectRoot, "app/src/main/res")
            } else {
                File(projectRoot, "src/main/res")
            }
        }

        private fun parse(file: File): Document =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

        /**
         * The picker's tag to the resource qualifier that actually holds it.
         *
         * `SUPPORTED_LANGUAGES` speaks BCP-47 (`zh-CN`, `zh-TW`), which is what
         * AppCompat and the system picker take; the resources use Android's
         * qualifiers instead, and the two do not spell Chinese the same way —
         * `values-zh` is Simplified and `values-zh-rTW` is one of the two
         * Traditional directories. Comparing the tag straight to a directory
         * asked for `values-zh-CN`, which has never existed.
         */
        private fun localeDir(tag: String): String = when (tag) {
            "zh-CN" -> "zh"
            "zh-TW" -> "zh-rTW"
            else -> tag
        }

        /** Keys that need translating: `translatable="false"` entries are excluded. */
        private fun translatableKeys(doc: Document): Set<String> {
            val keys = mutableSetOf<String>()
            for (tagName in listOf("string", "plurals", "string-array")) {
                val nodes = doc.getElementsByTagName(tagName)
                for (i in 0 until nodes.length) {
                    val element = nodes.item(i) as Element
                    if (element.getAttribute("translatable") == "false") continue
                    val name = element.getAttribute("name")
                    if (name.isNotEmpty()) keys.add(name)
                }
            }
            return keys
        }

        private val baseKeys: Set<String> by lazy {
            translatableKeys(parse(File(resDir, "values/strings.xml")))
        }
    }

    @Test
    fun strings_containsAllBaseKeys() {
        val dir = localeDir(tag)
        val localeFile = File(resDir, "values-$dir/strings.xml")
        assertTrue("values-$dir/strings.xml must exist", localeFile.exists())

        val missingKeys = baseKeys - translatableKeys(parse(localeFile))
        assertTrue(
            "values-$dir/strings.xml is missing ${missingKeys.size} keys from values/strings.xml: $missingKeys",
            missingKeys.isEmpty(),
        )
    }
}
