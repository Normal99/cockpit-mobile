package dev.normal99.cockpit.cockpit

import java.security.cert.X509Certificate

/** A failure reported by Cockpit: a "problem" code (access-denied, not-found, ...) and/or a message. */
class CockpitException(
    val problem: String?,
    message: String,
    val exitStatus: Int? = null,
) : Exception(message) {
    val isAccessDenied get() = problem == "access-denied" || problem == "not-authorized" ||
        message?.contains(Regex("access.denied|not authorized", RegexOption.IGNORE_CASE)) == true

    /** A message a person can act on. */
    fun friendly(): String = when {
        isAccessDenied -> "Needs administrative access. Turn on admin access for this host and try again."
        problem == "authentication-failed" -> "Wrong user name or password."
        problem == "disconnected" -> "Lost the connection to the host."
        problem == "timeout" -> "The host did not answer in time."
        problem == "no-cockpit" -> "Cockpit is not installed on that host."
        else -> message?.trim().orEmpty().ifEmpty { problem ?: "Unknown error" }
    }
}

/** The server presented a certificate that is neither publicly trusted nor the one pinned for this host. */
class UntrustedCertificateException(
    val certificate: X509Certificate,
    val sha256: String,
    val pinned: String?,
) : java.security.cert.CertificateException("Untrusted certificate $sha256")

fun Throwable.friendlyMessage(): String {
    var t: Throwable? = this
    while (t != null) {
        if (t is CockpitException) return t.friendly()
        if (t is UntrustedCertificateException) {
            return if (t.pinned == null) "The host's certificate is not trusted yet."
            else "The host's certificate CHANGED since you trusted it. This can mean someone is intercepting the connection."
        }
        if (t is java.net.UnknownHostException) return "Can't find that host name. Check the address (and Tailscale)."
        if (t is java.net.ConnectException) return "Can't reach the host. Is Cockpit running and the port right?"
        if (t is java.net.SocketTimeoutException) return "The host did not answer in time."
        if (t is java.net.ProtocolException && t.message?.contains("403") == true) {
            return "Cockpit refused this address. Use the address you use in the browser, or add it to Origins in /etc/cockpit/cockpit.conf."
        }
        t = t.cause
    }
    return message ?: toString()
}

fun Throwable.untrustedCertificate(): UntrustedCertificateException? {
    var t: Throwable? = this
    while (t != null) {
        if (t is UntrustedCertificateException) return t
        t = t.cause ?: t.suppressed.firstOrNull()
    }
    return null
}
