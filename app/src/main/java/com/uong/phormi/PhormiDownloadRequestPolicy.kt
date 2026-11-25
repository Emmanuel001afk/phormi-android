package com.uong.phormi

import java.net.URI
import java.util.Locale

/** Request-header rules for the browser's download transport. Kept JVM-testable. */
internal object PhormiDownloadRequestPolicy {
    private val credentialHeaders = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "cookie2",
        "x-api-key"
    )

    fun isHttpUrl(value: String): Boolean = parseHttpUri(value) != null

    fun addressReach(value: String, trustedHosts: Set<String>, allowLan: Boolean): PhormiNetworkPolicy.Reach =
        if (!isHttpUrl(value)) {
            PhormiNetworkPolicy.Reach(false, "A valid HTTP or HTTPS download URL is required.")
        } else {
            PhormiNetworkPolicy.reach(value, trustedHosts, allowLan)
        }

    /**
     * Merge browser headers with headers observed for this exact download URL.
     * Cookies are selected only for the destination URL; the referring page is never a
     * fallback because that can forward session cookies to a different host or scheme.
     */
    fun initialHeaders(
        defaults: Map<String, String>,
        observedForExactUrl: Map<String, String>,
        destinationUrl: String,
        destinationCookies: String?
    ): Map<String, String> {
        if (parseHttpUri(destinationUrl) == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        fun putHeader(name: String, value: String) {
            if (name.isBlank() || value.isBlank() || forbiddenTransportHeader(name)) return
            result.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(result::remove)
            result[name] = value
        }
        fun copyNonCookie(headers: Map<String, String>) {
            headers.forEach { (name, value) ->
                if (!name.equals("cookie", true) && !name.equals("cookie2", true) &&
                    !name.equals("referer", true) && !name.equals("origin", true)) {
                    putHeader(name, value)
                }
            }
        }
        copyNonCookie(defaults)
        copyNonCookie(observedForExactUrl)

        val targetCookie = destinationCookies?.takeIf { it.isNotBlank() }
            ?: header(observedForExactUrl, "Cookie")?.takeIf { it.isNotBlank() }
        targetCookie?.let { putHeader("Cookie", it) }

        val referer = header(defaults, "Referer") ?: header(observedForExactUrl, "Referer")
        safeReferer(destinationUrl, referer)?.let { putHeader("Referer", it) }
        val source = origin(referer.orEmpty())
        val destination = origin(destinationUrl)
        if (source != null && destination != null && source.scheme == "https" && destination.scheme == "https") {
            putHeader("Origin", source.asUrl())
        }
        return result
    }

    /** Keep only ordinary, non-secret headers in the durable download record. */
    fun headersForPersistence(headers: Map<String, String>): Map<String, String> =
        headers.filter { (name, _) ->
            !isCredentialHeader(name) &&
                !name.equals("referer", true) &&
                !name.equals("origin", true)
        }

    /** Redirects can retain credentials only when the target remains the same origin. */
    fun headersForRedirect(fromUrl: String, toUrl: String, headers: Map<String, String>): Map<String, String> {
        if (sameOrigin(fromUrl, toUrl)) return headers.toMap()
        return headers.filterKeys { name ->
            !isCredentialHeader(name) &&
                !name.equals("referer", true) &&
                !name.equals("origin", true)
        }
    }

    fun resolveRedirect(fromUrl: String, location: String?): String? {
        val base = parseHttpUri(fromUrl) ?: return null
        val target = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { base.resolve(URI(target)).normalize().toASCIIString() }
            .getOrNull()
            ?.takeIf(::isHttpUrl)
    }

    fun isRedirectCode(code: Int): Boolean = code in REDIRECT_CODES

    fun sameOrigin(first: String, second: String): Boolean {
        val left = origin(first) ?: return false
        val right = origin(second) ?: return false
        return left == right
    }

    fun isCredentialHeader(name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.ROOT)
        return normalized in credentialHeaders || normalized.contains("cookie") ||
            normalized.endsWith("-token") || normalized.endsWith("-secret") ||
            normalized.contains("api-key") || normalized.contains("api_key") || normalized.contains("apikey") ||
            normalized.contains("access-key") || normalized.contains("access_key") ||
            normalized.contains("private-key") || normalized.contains("private_key") ||
            normalized.contains("csrf") || normalized.contains("xsrf") ||
            normalized.contains("session") || normalized.contains("password") ||
            normalized.contains("credential") || normalized.contains("auth")
    }

    private fun forbiddenTransportHeader(name: String): Boolean =
        name.equals("Host", true) || name.equals("Content-Length", true) ||
            name.equals("Transfer-Encoding", true) || name.equals("Connection", true)

    private fun safeReferer(destinationUrl: String, rawReferer: String?): String? {
        val source = rawReferer?.let(::origin) ?: return null
        val destination = origin(destinationUrl) ?: return null
        if (source == destination) return rawReferer
        if (source.scheme == "https" && destination.scheme == "https") return source.asUrl() + "/"
        return null
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private data class Origin(val scheme: String, val host: String, val port: Int) {
        fun asUrl(): String = "$scheme://$host${if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) "" else ":$port"}"
    }

    private fun origin(value: String): Origin? {
        val uri = parseHttpUri(value) ?: return null
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT).removeSuffix(".")
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        return Origin(scheme, host, port)
    }

    private fun parseHttpUri(value: String): URI? = runCatching {
        URI(value.trim()).takeIf {
            it.scheme?.lowercase(Locale.ROOT) in WEB_SCHEMES &&
                !it.host.isNullOrBlank() && it.rawUserInfo == null &&
                (it.port == -1 || it.port in 1..65535)
        }
    }.getOrNull()

    private val WEB_SCHEMES = setOf("http", "https")
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
}
