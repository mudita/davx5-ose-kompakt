/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.network

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Maps the app's signing certificate to the Google OAuth client ID registered for it.
 *
 * Google ties an Android OAuth client to a **package name + SHA-1 certificate fingerprint** pair,
 * so a build signed with a different key needs a different `CLIENT_ID`. At runtime we read the
 * SHA-1 fingerprint of the certificate the running app was signed with and pick the matching
 * client ID (see [clientId]).
 *
 * To add a build/signing key: obtain its SHA-1 fingerprint and register an OAuth client for
 * `{applicationId + that SHA-1}` in Google Cloud Console, then add a [SignatureClient] entry below.
 */
object KompaktGoogleOAuthClients {

    private val clients = listOf(
        SignatureClient(
            sha1 = "27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA",
            clientId = "1044477190035-8pja9fc38b3tq57ipqqqckpka61f0blr.apps.googleusercontent.com"
        ),
        SignatureClient(
            sha1 = "5F:3C:FD:0C:44:E8:E9:9F:3D:0A:47:38:66:9C:2F:79:83:A3:75:DC",
            clientId = "1044477190035-e3frhd41q5p9r5r2tnlgl0l031cq38jt.apps.googleusercontent.com"
        ),
    )

    private const val FALLBACK_CLIENT_ID =
        "1044477190035-8pja9fc38b3tq57ipqqqckpka61f0blr.apps.googleusercontent.com"

    data class SignatureClient(val sha1: String, val clientId: String)

    /**
     * The Google OAuth client ID matching the running app's signing certificate, or
     * [FALLBACK_CLIENT_ID] when the current signature matches none of the configured [clients]
     * (or cannot be read).
     */
    fun clientId(context: Context): String =
        clientIndex(context).let { index ->
            if (index >= 0) clients[index].clientId else FALLBACK_CLIENT_ID
        }

    /**
     * Index of the [clients] entry whose SHA-1 matches the running app's signing certificate:
     * `0` for the first configured key, `1` for the second, and `-1` when the signature matches
     * none of them (then [clientId] falls back to [FALLBACK_CLIENT_ID]) or cannot be read.
     *
     * Logged during sign-in to record which registered signing key a build was recognized as,
     * without exposing the fingerprint or client ID in logs.
     */
    fun clientIndex(context: Context): Int {
        val current = currentSha1(context)?.normalizeFingerprint() ?: return -1
        return clients.indexOfFirst { it.sha1.normalizeFingerprint() == current }
    }

    /**
     * SHA-1 fingerprint of the certificate the running app is signed with, as colon-separated
     * upper-case hex (e.g. `AB:CD:…`) — identical to the value printed by `keytool`/`apksigner`
     * and shown in Google Cloud Console. `null` if it cannot be determined.
     *
     * Public so the flow can log it, making it easy to read the exact fingerprint to register.
     */
    fun currentSha1(context: Context): String? =
        signingCertificate(context)?.let { fingerprint(it.toByteArray()) }

    private fun signingCertificate(context: Context): Signature? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            info?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")  // required on API < 28
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** SHA-1 of the DER-encoded X.509 certificate bytes, as colon-separated upper-case hex. */
    private fun fingerprint(certBytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1")
            .digest(certBytes)
            .joinToString(":") { "%02X".format(it) }

    private fun String.normalizeFingerprint() = filter { it != ':' && !it.isWhitespace() }.uppercase()

}
