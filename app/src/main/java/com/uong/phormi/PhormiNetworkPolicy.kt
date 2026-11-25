package com.uong.phormi

import java.net.URI
import java.util.Locale

/**
 * Address policy for everything that is not ordinary public web traffic.
 *
 * A browser has to open the addresses people actually use on a phone. Phormi used
 * to reject cleartext HTTP outright (`network_security_config` + a URI check), so a
 * LAN server such as `http://192.168.1.20:8080` could not be opened at all - Chrome
 * opens it, warns about it, and offers a one-tap override when needed.
 *
 * Rules kept from before:
 * - Public sites stay HTTPS-only. Cleartext is never silently allowed there.
 * - No arbitrary schemes, no `file:` browsing, no inline credentials in a URL.
 *
 * Rules added:
 * - Loopback, private LAN ranges, `.local` names, single-label hostnames
 *   (`http://files:8080`) and already-known LAN destinations may use HTTP.
 * - A user who explicitly types `http://` for a public host reaches a clear,
 *   dismissible "continue anyway" prompt instead of being silently blocked.
 */
object PhormiNetworkPolicy {
    /** Hosts the user already opened in cleartext, so revisits do not re-prompt. */
    const val KEY_CLEARTEXT_HOSTS = "cleartext_trusted_hosts"
    const val KEY_ALLOW_LAN_HTTP = "allow_lan_http"

    data class Reach(val allowed: Boolean, val reason: String)

    fun host(url: String?): String {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return runCatching {
            (URI(value).host ?: "").lowercase(Locale.ROOT).removePrefix("www.")
        }.getOrDefault("").removeSuffix(".")
    }

    fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".localhost")

    /** RFC1918, CGNAT, link-local and mDNS names: the phone's own network. */
    fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.lowercase(Locale.ROOT).removeSuffix(".")
            .removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return false
        if (isLoopback(host) || host.endsWith(".local") || host.endsWith(".lan") ||
            host.endsWith(".home.arpa") || host.endsWith(".internal")) return true
        fun octets(value: String): IntArray? {
            val parts = value.split('.')
            if (parts.size != 4) return null
            val nums = parts.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
            return if (nums.size == 4) nums.toIntArray() else null
        }
        octets(host)?.let { o ->
            return when {
                o[0] == 10 -> true
                o[0] == 172 && o[1] in 16..31 -> true
                o[0] == 192 && o[1] == 168 -> true
                o[0] == 169 && o[1] == 254 -> true
                o[0] == 100 && o[1] in 64..127 -> true
                else -> false
            }
        }
        // IPv6 unique-local (fc00::/7) and link-local (fe80::/10).
        if (host.contains(':')) {
            if (host.startsWith("fc") || host.startsWith("fd")) return true
            if (host.startsWith("fe8") || host.startsWith("fe9") ||
                host.startsWith("fea") || host.startsWith("feb")) return true
        }
        // A bare single-label name (`http://nas`, `http://files:8080`) is an on-network
        // device, not a public domain, which always has at least one dot.
        return !host.contains('.') && !host.contains(':')
    }

    fun isCleartext(url: String?): Boolean = url?.trim()?.startsWith("http://", true) == true

    /**
     * @param trustedHosts hosts the user already chose to open over HTTP
     * @param allowLan whether site information should apply the local-network label
     */
    fun reach(
        url: String?,
        trustedHosts: Set<String> = emptySet(),
        allowLan: Boolean = true
    ): Reach {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return Reach(false, "The address is empty.")
        if (value.startsWith("about:", true) || value.startsWith("https://phormi.local/", true)) {
            return Reach(true, "New tab")
        }
        val parsed = runCatching { URI(value) }.getOrNull()
            ?: return Reach(false, "The address is not a valid web URL.")
        val scheme = parsed.scheme?.lowercase(Locale.ROOT).orEmpty()
        if (scheme != "http" && scheme != "https") return Reach(false, "Only http and https addresses can be opened.")
        if (parsed.host.isNullOrBlank() || parsed.rawUserInfo != null) {
            return Reach(false, "A valid web host is required; embedded username and password are not allowed.")
        }
        if (scheme == "https") return Reach(true, "Secure connection")
        val target = host(value)
        val trusted = trustedHosts.map { it.lowercase(Locale.ROOT).removePrefix("www.") }.toSet()
        if (target.isNotEmpty() && target in trusted) return Reach(true, "You chose to open this address over HTTP.")
        // A private target remains usable regardless of the display preference. The
        // setting controls the extra local-network label, not whether the URL can load.
        if (isPrivateHost(target)) return Reach(
            true,
            if (allowLan) "Local network page over HTTP" else "HTTP page; local-network label disabled"
        )
        return Reach(
            false,
            "This address uses HTTP, which is not encrypted. " +
                "Phormi keeps public sites on HTTPS; continue only if you trust ${target.ifBlank { "this address" }}."
        )
    }

    /** Copy shown in the "continue over HTTP" prompt. */
    fun warningMessage(url: String): String {
        val target = host(url).ifBlank { url.orEmpty() }
        return "http:// addresses are not encrypted, so someone on the same network could read or change what is sent.\n\n" +
            "You asked to open:\n$url\n\nPurpose: browsing a site that has not enabled HTTPS, or a device on your local network.\n" +
            "Continue only if you trust $target."
    }

    /** Remember a host only after the user deliberately continued. */
    fun withTrustedHost(trusted: Set<String>, url: String): Set<String> {
        val target = host(url)
        if (target.isBlank()) return trusted
        return (trusted + target).toSet()
    }
}
