package io.github.qaisarpipeshift.utter.data

import io.github.qaisarpipeshift.utter.net.normalizeBaseUrl
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The `utter://setup` link shared between phones (docs/spec.md §17, docs/plan.md Task 12).
 *
 * Why a link at all: setting up a second phone means typing a URL and a 48-character key. The link carries both, so
 * the wizard only needs one tap. It is deliberately a plain custom scheme with a fixed host — no website, no
 * verification — because every byte that matters is validated here before it reaches settings.
 *
 * Security: [build] refuses anything that is not a usable `https://` server and a non-blank key; [parse] applies the
 * same rules to input, so a hostile link can never point the keyboard at a cleartext server. The key is URL-encoded
 * so `&`, `+`, `=` and `/` survive the round trip.
 */
object SetupLink {

    /** The two values a link carries, already validated. */
    data class Data(val url: String, val key: String)

    /** `utter://setup?u=<urlenc>&k=<urlenc>`, or null when either value is unusable. */
    fun build(rawUrl: String, key: String): String? {
        val url = normalizeBaseUrl(rawUrl) ?: return null
        val apiKey = key.trim()
        if (apiKey.isEmpty()) return null
        return "utter://setup?u=${encode(url)}&k=${encode(apiKey)}"
    }

    /** Reads a link back; null for a wrong scheme/host, a non-https URL, a missing key, or broken encoding. */
    fun parse(raw: String): Data? {
        val marker = "utter://setup"
        val trimmed = raw.trim()
        if (!trimmed.startsWith(marker, ignoreCase = true)) return null
        var rest = trimmed.substring(marker.length)
        if (rest.startsWith("/")) rest = rest.substring(1)
        if (!rest.startsWith("?")) return null
        val query = rest.substring(1).substringBefore('#')

        var url: String? = null
        var key: String? = null
        for (part in query.split('&')) {
            val separator = part.indexOf('=')
            if (separator <= 0) continue
            val name = part.substring(0, separator)
            val value = decode(part.substring(separator + 1)) ?: return null
            when (name) {
                "u" -> url = value
                "k" -> key = value
            }
        }
        val normalized = url?.let { normalizeBaseUrl(it) } ?: return null
        val apiKey = key?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Data(normalized, apiKey)
    }

    private fun encode(value: String): String = try {
        URLEncoder.encode(value, CHARSET)
    } catch (_: UnsupportedEncodingException) {
        value
    }

    private fun decode(value: String): String? = try {
        URLDecoder.decode(value, CHARSET)
    } catch (_: UnsupportedEncodingException) {
        null
    } catch (_: IllegalArgumentException) {
        // A malformed escape such as "%ZZ"; a broken link must open the app, not crash it.
        null
    }

    private const val CHARSET = "UTF-8"
}
