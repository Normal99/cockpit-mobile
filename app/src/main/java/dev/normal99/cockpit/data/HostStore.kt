package dev.normal99.cockpit.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.normal99.cockpit.cockpit.arr
import dev.normal99.cockpit.cockpit.bool
import dev.normal99.cockpit.cockpit.json
import dev.normal99.cockpit.cockpit.jsonOf
import dev.normal99.cockpit.cockpit.obj
import dev.normal99.cockpit.cockpit.str
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray

data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val user: String,
    /** SHA-256 of the certificate the person accepted (Cockpit's self-signed one); null = only publicly trusted certs. */
    val pinnedSha256: String? = null,
    /** Log in with "Reuse my password for privileged tasks" (admin access), like the web login's checkbox. */
    val admin: Boolean = true,
    /** Notify about root requests waiting for approval (Claude page hosts). */
    val notify: Boolean = true,
)

/** Saved hosts in private prefs; passwords encrypted with a key that never leaves the Android Keystore. */
class HostStore(context: Context) {
    private val prefs = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)
    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<Host>> = _hosts.asStateFlow()

    private val _current = MutableStateFlow(prefs.getString("current", null))
    val current: StateFlow<String?> = _current.asStateFlow()

    fun host(id: String?) = _hosts.value.firstOrNull { it.id == id }

    fun select(id: String) {
        prefs.edit().putString("current", id).apply()
        _current.value = id
    }

    fun save(host: Host, password: String?) {
        val exists = _hosts.value.any { it.id == host.id }
        val list = if (exists) _hosts.value.map { if (it.id == host.id) host else it } else _hosts.value + host
        if (password != null) prefs.edit().putString("pw:${host.id}", SecretBox.encrypt(password)).apply()
        persist(list)
        if (_current.value == null || host(_current.value) == null) select(host.id)
    }

    fun delete(id: String) {
        prefs.edit().remove("pw:$id").remove("seen:$id").apply()
        persist(_hosts.value.filter { it.id != id })
        if (_current.value == id) {
            val next = _hosts.value.firstOrNull()?.id
            prefs.edit().putString("current", next).apply()
            _current.value = next
        }
    }

    fun password(id: String): String? = prefs.getString("pw:$id", null)?.let { runCatching { SecretBox.decrypt(it) }.getOrNull() }

    /** Root request ids already notified, so each one alerts once. */
    fun seenRequests(id: String): Set<String> = prefs.getStringSet("seen:$id", emptySet()) ?: emptySet()
    fun setSeenRequests(id: String, ids: Set<String>) = prefs.edit().putStringSet("seen:$id", ids.toSet()).apply()

    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(v) = prefs.edit().putBoolean("app_lock", v).apply()

    private fun persist(list: List<Host>) {
        val arr = JsonArray(list.map {
            jsonOf("id" to it.id, "name" to it.name, "url" to it.url, "user" to it.user, "pin" to it.pinnedSha256,
                "admin" to it.admin, "notify" to it.notify)
        })
        prefs.edit().putString("list", arr.toString()).apply()
        _hosts.value = list
    }

    private fun load(): List<Host> {
        val text = prefs.getString("list", null) ?: return emptyList()
        return runCatching { json.parseToJsonElement(text).arr() }.getOrElse { JsonArray(emptyList()) }.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            Host(o.str("id") ?: return@mapNotNull null, o.str("name") ?: "", o.str("url") ?: return@mapNotNull null, o.str("user") ?: "",
                o.str("pin"), o.bool("admin") ?: true, o.bool("notify") ?: true)
        }
    }
}

object SecretBox {
    private const val ALIAS = "cockpit-mobile-secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12)) }
        return String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }
}
