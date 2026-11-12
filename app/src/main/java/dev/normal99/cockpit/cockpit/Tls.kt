package dev.normal99.cockpit.cockpit

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import javax.net.ssl.HttpsURLConnection

fun sha256Fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

/**
 * Accepts publicly trusted certificates (e.g. Tailscale's HTTPS certs) as usual, and otherwise only the one
 * certificate the person pinned for this host (Cockpit's default self-signed cert). Trust on first use.
 */
// Lint's CustomX509TrustManager: deliberate. This only adds one exact certificate (by SHA-256) that the person confirmed.
@android.annotation.SuppressLint("CustomX509TrustManager")
class PinningTrustManager(private val pinned: String?) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("Empty certificate chain")
        val leaf = chain[0]
        val fp = sha256Fingerprint(leaf)
        if (pinned != null && fp.equals(pinned, ignoreCase = true)) return
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            throw UntrustedCertificateException(leaf, fp, pinned)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("Client certificates are not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/** A pinned certificate is trusted for its host whatever name it carries (Cockpit's cert names the machine, not the address). */
class PinningHostnameVerifier(private val pinned: String?) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (pinned != null) {
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            if (leaf != null && sha256Fingerprint(leaf).equals(pinned, ignoreCase = true)) return true
        }
        return HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
    }
}

fun httpClientFor(pinnedSha256: String?): OkHttpClient {
    val tm = PinningTrustManager(pinnedSha256)
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    return OkHttpClient.Builder()
        .sslSocketFactory(ctx.socketFactory, tm)
        .hostnameVerifier(PinningHostnameVerifier(pinnedSha256))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
}
