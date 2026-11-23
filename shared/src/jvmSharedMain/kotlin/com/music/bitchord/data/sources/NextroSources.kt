package com.music.bitchord.data.sources

/**
 * The addon gateway this build ships pointed at.
 *
 * The lossless source is not a protocol of its own: it is an ordinary addon
 * served by nextro's own gateway, so the app needs no new [SourceKind] parser
 * for it — only a built-in config that points at the gateway and cannot be
 * removed. Keeping the URL here rather than in each app means the phone and the
 * desktop cannot drift apart on which gateway they were built against.
 *
 * The gateway itself fronts the catalogue and returns a media URL; the app
 * speaks the same `/manifest.json` → `/search` → `/stream` protocol it speaks
 * to any addon the user added.
 */
object NextroSources {
    /** The gateway root. The addon protocol appends `/manifest.json`, `/search`, `/stream`. */
    const val HIGH_RES_URL = "https://addon.krkyhost.info"

    /** What the row is called, overriding the name the gateway's manifest publishes. */
    const val HIGH_RES_LABEL = "nextro's High-Res Source"

    /**
     * The spelling shipped by earlier builds, with a capital N.
     *
     * Kept only so stored configs can be repaired: the built-in is a proper
     * name and the gateway's own manifest must not rename it. A label the user
     * typed is never in this set and is therefore never touched.
     */
    const val LEGACY_HIGH_RES_LABEL = "Nextro's High-Res Source"

    /** Stable id for the seeded desktop config; the phone generates one like any other source. */
    const val HIGH_RES_ID = "nextro-high-res"

    /**
     * The built-in gateway's label for a stored or seeded value.
     *
     * Blank means the config never had one, and the legacy spelling means it
     * has not been opened since the name was fixed. Anything else is the
     * listener's own name and is returned unchanged.
     */
    fun normalizeHighResLabel(label: String): String = when (label) {
        "", LEGACY_HIGH_RES_LABEL -> HIGH_RES_LABEL
        else -> label
    }
}
