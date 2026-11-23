package com.music.bitchord.data.sources

import java.util.Locale

/**
 * Which upstream behind a nextrobyte addon actually served a track.
 *
 * The gateway fronts several backends — arcod, monochrome, netease, the
 * lossless bridge, and user-named backends such as emika. A current gateway
 * states the one that answered in the `X-Nextro-Source` response header on
 * `/stream/{id}`; an older deployment does not, and the track id is then the
 * only evidence there is.
 *
 * Both paths are kept here, in `:shared`, so the phone and the desktop cannot
 * disagree about what `gd_` means or about how "bridge" is spelled on screen.
 * The header wins when present: a gateway that has been redeployed to route
 * the same id somewhere else is the authority, and the id prefixes are a
 * historical fact about how the backend names its rows, not a routing table.
 *
 * Every provider is lower case. `bridge` is the gateway's name for the
 * lossless upstream; listeners are shown `lossless`, which is the thing they
 * asked for and the only name with meaning outside the gateway's internals.
 */
object AddonUpstreams {

    /** The response header a current gateway uses to name the upstream that answered. */
    const val HEADER = "X-Nextro-Source"

    /** The provider shown for the gateway's lossless bridge. */
    const val LOSSLESS = "lossless"

    private const val BRIDGE = "bridge"

    /**
     * Legacy id prefixes and the upstream each one names.
     *
     * These are the ids nextro's gateway minted before it knew which backend
     * answered. They stay supported because a queue, a cache entry or a
     * download written by an older build still carries them.
     */
    private val LEGACY_PREFIXES = linkedMapOf(
        "ar_" to "arcod",
        "gd_" to "netease",
        "td_" to "monochrome",
        "ne_" to BRIDGE,
        "qq_" to BRIDGE,
    )

    /** `sourceId:base64url` — newer addons namespace their own opaque ids. */
    private val NAMESPACED = Regex("""^[a-z0-9][a-z0-9._-]{0,31}$""")
    private val BASE64URL = Regex("""^[A-Za-z0-9_=-]+$""")

    /**
     * Namespaces that are catalogue backends, not gateway upstreams.
     *
     * The live addon's ids include `tidal:417594671`; the namespace there is
     * the service the row came from, and showing it as a nextro upstream would
     * be a different fact dressed as this one. Unknown namespaces are still
     * accepted — emika and whatever comes next are the point of the form.
     */
    private val BACKEND_NAMESPACES = setOf(
        "tidal", "deezer", "qobuz", "spotify", "jiosaavn", "youtube",
        "soundcloud", "amazon", "apple", "kkbox",
    )

    /**
     * What a gateway stated, reduced to the form stored everywhere: trimmed,
     * lower-cased, and refused when it is not a provider-shaped token.
     *
     * Null means "said nothing usable", never "no provider": an unknown
     * provider is still worth showing rather than silently dropping to null.
     */
    fun canonical(raw: String?): String? {
        val value = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return value.takeIf { NAMESPACED.matches(it) }
    }

    /**
     * The readable name for a provider: [canonical]'s value, with `bridge`
     * shown as [LOSSLESS]. Null in, null out.
     */
    fun displayName(provider: String?): String? =
        canonical(provider)?.let { if (it == BRIDGE) LOSSLESS else it }

    /**
     * The upstream named by an addon's own track id, when the id still says.
     *
     * Legacy ids are matched first because they are prefixes, not namespaces:
     * `ar_123` must be arcod even though it contains no colon. A namespaced id
     * is `<sourceId>:<base64url>`; the payload is only checked well enough to
     * tell it apart from some other protocol's colon-laden id, since the
     * namespace is the fact being read.
     */
    fun fromTrackId(trackId: String?): String? {
        val id = trackId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = id.lowercase(Locale.ROOT)
        LEGACY_PREFIXES.entries.firstOrNull { lower.startsWith(it.key) }?.let { return it.value }

        val colon = id.indexOf(':')
        if (colon <= 0 || colon == id.lastIndex) return null
        val namespace = id.substring(0, colon).lowercase(Locale.ROOT)
        val payload = id.substring(colon + 1)
        if (namespace in BACKEND_NAMESPACES) return null
        if (!NAMESPACED.matches(namespace) || !BASE64URL.matches(payload)) return null
        return canonical(namespace)
    }

    /**
     * The provider for a stream: what the gateway said, else what the id says.
     *
     * Kept as one call so a caller cannot accidentally prefer the id over a
     * header that is right there — which is how a gateway that has changed
     * backends keeps reporting the old one.
     */
    fun resolve(header: String?, trackId: String?): String? =
        canonical(header) ?: fromTrackId(trackId)
}
