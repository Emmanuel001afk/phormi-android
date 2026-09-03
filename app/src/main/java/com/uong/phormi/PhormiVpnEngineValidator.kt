package com.uong.phormi

import android.content.Context
import android.os.Debug
import java.io.File

/**
 * Runtime-only validation for a future embedded OpenVPN native engine.
 *
 * This class deliberately does not create a VPN, open a TUN, or connect to a server.
 * It answers whether the currently installed Phormi APK contains a candidate native
 * engine and measures the app process memory so the 500 MB product limit can be checked.
 */
object PhormiVpnEngineValidator {
    const val MAX_ALLOWED_PSS_KB = 500 * 1024

    data class Result(
        val embeddedEnginePresent: Boolean,
        val nativeLibraries: List<String>,
        val baselinePssKb: Int,
        val thresholdKb: Int,
        val withinMemoryLimit: Boolean,
        val externalOpenVpnInstalled: Boolean,
        val paidServiceRequired: Boolean = false,
        val note: String
    )

    fun inspect(context: Context): Result {
        val nativeDir = context.applicationInfo.nativeLibraryDir?.let(::File)
        val libraries = nativeDir?.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

        val candidates = libraries.filter { name ->
            val n = name.lowercase()
            "openvpn" in n || "ovpn" in n || "vpn3" in n
        }

        val pssKb = currentPssKb()
        val external = try {
            context.packageManager.getPackageInfo("de.blinkt.openvpn", 0)
            true
        } catch (_: Exception) {
            false
        }

        return Result(
            embeddedEnginePresent = candidates.isNotEmpty(),
            nativeLibraries = candidates,
            baselinePssKb = pssKb,
            thresholdKb = MAX_ALLOWED_PSS_KB,
            withinMemoryLimit = pssKb <= MAX_ALLOWED_PSS_KB,
            externalOpenVpnInstalled = external,
            note = if (candidates.isEmpty()) {
                "No embedded OpenVPN native engine is packaged in this APK."
            } else {
                "Candidate embedded VPN library detected; a real engine run must be measured separately."
            }
        )
    }

    /** Measure this app process's current proportional set size (PSS). */
    fun currentPssKb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.coerceAtLeast(0)
    }

    /**
     * Try loading a candidate native library without starting it.
     * Returns a human-readable result and never throws to the caller.
     */
    fun tryLoadNativeLibrary(libraryName: String): String {
        val name = libraryName.trim()
        if (name.isBlank()) return "No library name supplied."
        return try {
            System.loadLibrary(name)
            "Native library '$name' loaded successfully."
        } catch (e: UnsatisfiedLinkError) {
            "Native library '$name' could not be loaded: ${e.message ?: "link error"}"
        } catch (e: SecurityException) {
            "Native library '$name' could not be loaded: ${e.message ?: "security restriction"}"
        } catch (e: Exception) {
            "Native library '$name' could not be loaded: ${e.message ?: "unknown error"}"
        }
    }

    fun format(result: Result): String {
        val memoryMb = result.baselinePssKb / 1024.0
        val limitMb = result.thresholdKb / 1024
        val engine = if (result.embeddedEnginePresent) "present" else "not packaged"
        val memory = if (result.withinMemoryLimit) "within" else "OVER"
        val external = if (result.externalOpenVpnInstalled) "installed" else "not installed"
        return buildString {
            append("Embedded engine: $engine\n")
            if (result.nativeLibraries.isNotEmpty()) {
                append("Candidates: ${result.nativeLibraries.joinToString()}\n")
            }
            append("Current app PSS: ${"%.1f".format(memoryMb)} MB / ${limitMb} MB limit ($memory)\n")
            append("External OpenVPN app: $external\n")
            append("Paid service requirement: ${if (result.paidServiceRequired) "yes" else "none declared"}\n")
            append(result.note)
        }
    }
}
