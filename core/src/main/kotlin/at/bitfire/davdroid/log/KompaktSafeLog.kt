/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.log

import net.openid.appauth.AuthorizationException
import java.util.logging.Filter
import java.util.logging.LogRecord

/**
 * Kompakt: a description of this [Throwable] that is safe to log from OAuth code paths.
 *
 * Never includes exception messages or stack traces: when AppAuth fails to parse a (e.g. truncated)
 * token response, it wraps an `org.json.JSONException` whose message contains the whole response
 * body — including the refresh, access and ID tokens. Passing such an exception to the logger would
 * print that message (as "Caused by: …") to logcat, the log file and debug-info archives.
 *
 * Only structured fields are used: for [AuthorizationException] its `type`, `code` and — only if it's
 * a standard OAuth error code (the field is copied verbatim from the server response) — `error`; for
 * every exception in the cause chain only its class name.
 */
fun Throwable.kompaktSafeDescription(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    while (current != null && parts.size < MAX_CAUSES) {
        val e: Throwable = current
        parts += if (e is AuthorizationException)
            "AuthorizationException(type=${e.type}, code=${e.code}, error=${safeOAuthError(e.error)})"
        else
            e.javaClass.name
        current = e.cause.takeIf { it !== e }
    }
    return parts.joinToString(" caused by ")
}

private const val MAX_CAUSES = 5

/** Standard OAuth 2.0 / OpenID Connect error codes (RFC 6749 4.1.2.1/5.2, OIDC Core 3.1.2.6). */
private val KNOWN_OAUTH_ERRORS = setOf(
    "invalid_request", "invalid_client", "invalid_grant", "unauthorized_client",
    "unsupported_grant_type", "unsupported_response_type", "invalid_scope", "access_denied",
    "server_error", "temporarily_unavailable",
    "interaction_required", "login_required", "account_selection_required", "consent_required",
    "invalid_request_uri", "invalid_request_object", "request_not_supported",
    "request_uri_not_supported", "registration_not_supported"
)

private fun safeOAuthError(error: String?): String? =
    when (error) {
        null -> null
        in KNOWN_OAUTH_ERRORS -> error
        else -> "(non-standard)"
    }


/** `scheme://` or protocol-relative `//`, followed by user info up to `@` (user info is percent-encoded, so no `/`). */
private val URL_USER_INFO = Regex("""(?i)((?:\b[a-z][a-z0-9+.-]*:)?//)[^/\s@?#"'<>]+@""")

/**
 * A URL-like token — absolute (`scheme://…`), protocol-relative (`//host/…`) or an absolute path (`/…`)
 * starting at a token boundary — followed by a query and/or fragment, which is captured up to the
 * next whitespace, quote or angle bracket.
 */
private val URL_QUERY_OR_FRAGMENT = Regex(
    """(?i)((?:\b[a-z][a-z0-9+.-]*:)?//[^\s?#"'<>]*|(?:^|(?<=[\s"'<>(=:,;]))/[^\s?#"'<>]*)([?#])[^\s"'<>]*"""
)

/**
 * Kompakt: removes secrets from URLs in a log line — user info (`user:password@`) and everything from
 * the query (`?`) or fragment (`#`) on, which may contain tokens (e.g. `?token=…`, `#access_token=…`).
 * Covers absolute, protocol-relative and path-only URLs (e.g. in `Location` headers). Idempotent.
 */
fun kompaktSanitizeHttpLogLine(line: String): String =
    line.replace(URL_USER_INFO, "$1***@")
        .replace(URL_QUERY_OR_FRAGMENT, "$1$2***")


/**
 * Kompakt: a [Filter] that sanitizes every record of the logger it is set on — message, parameters and
 * the thrown exception chain (exception messages can contain URLs too, e.g. `URISyntaxException`) —
 * with [kompaktSanitizeHttpLogLine] before any handler sees it. Never drops a record.
 *
 * A logger's filter runs before both its own handlers and the parent handlers it forwards to.
 */
object KompaktSanitizingLogFilter : Filter {

    override fun isLoggable(record: LogRecord): Boolean {
        record.message = record.message?.let(::kompaktSanitizeHttpLogLine)
        record.parameters = record.parameters?.map { param ->
            param?.let { kompaktSanitizeHttpLogLine(it.toString()) }
        }?.toTypedArray()
        record.thrown = record.thrown?.let { sanitizedThrowable(it, depth = 0) }
        return true
    }

    private fun sanitizedThrowable(original: Throwable, depth: Int): Throwable {
        if (original is SanitizedThrowable)
            return original
        val cause = original.cause
            ?.takeIf { it !== original && depth < MAX_CAUSES }
            ?.let { sanitizedThrowable(it, depth + 1) }
        return SanitizedThrowable(original, cause)
    }

    /** Keeps the original class name and stack trace, but with a sanitized message. */
    private class SanitizedThrowable(original: Throwable, cause: Throwable?) : Throwable(
        original.javaClass.name + (original.message?.let { ": " + kompaktSanitizeHttpLogLine(it) } ?: ""),
        cause
    ) {
        init {
            stackTrace = original.stackTrace
        }

        // printed instead of "SanitizedThrowable: …" in stack traces
        override fun toString() = message ?: ""
    }

}
