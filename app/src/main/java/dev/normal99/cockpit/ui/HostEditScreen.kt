package dev.normal99.cockpit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.normal99.cockpit.claude.Tone
import dev.normal99.cockpit.cockpit.CockpitSession
import dev.normal99.cockpit.cockpit.Endpoint
import dev.normal99.cockpit.cockpit.UntrustedCertificateException
import dev.normal99.cockpit.cockpit.friendlyMessage
import dev.normal99.cockpit.cockpit.httpClientFor
import dev.normal99.cockpit.cockpit.untrustedCertificate
import dev.normal99.cockpit.data.Host
import dev.normal99.cockpit.data.HostStore
import java.text.DateFormat
import kotlinx.coroutines.launch

/** Add or edit a host. "Connect" logs in for real before saving, so a saved host is known to work. */
@Composable
fun HostEditScreen(store: HostStore, existing: Host?, onSaved: (Host) -> Unit, onDeleted: (() -> Unit)?, onDemo: (() -> Unit)? = null) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var address by remember { mutableStateOf(existing?.url ?: "") }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var admin by remember { mutableStateOf(existing?.admin ?: true) }
    var notify by remember { mutableStateOf(existing?.notify ?: true) }
    var pin by remember { mutableStateOf(existing?.pinnedSha256) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cert by remember { mutableStateOf<UntrustedCertificateException?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val normalized = runCatching { Endpoint.normalize(address).takeIf { address.isNotBlank() } }.getOrNull()

    fun connect() {
        val url = normalized ?: return
        val pw = password.ifEmpty { existing?.let { store.password(it.id) } ?: "" }
        busy = true
        error = null
        scope.launch {
            try {
                val session = CockpitSession(Endpoint(url), httpClientFor(pin))
                session.connect(user.trim(), pw, admin)
                session.disconnect()
                val host = (existing ?: Host(name = "", url = url, user = "")).copy(
                    name = name.trim().ifEmpty { Endpoint(url).base.host.substringBefore('.') },
                    url = url, user = user.trim(), pinnedSha256 = pin, admin = admin, notify = notify,
                )
                store.save(host, pw)
                onSaved(host)
            } catch (e: Throwable) {
                val c = e.untrustedCertificate()
                if (c != null) cert = c else error = e.friendlyMessage()
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (existing == null) "Add a Cockpit host" else "Edit ${existing.name}", style = MaterialTheme.typography.headlineSmall)
        Muted("Log in the same way as on Cockpit's web login page. Your password is stored encrypted with a key held by Android's Keystore.")
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Address") },
            placeholder = { Text("optiplex.tailnet.ts.net or 192.168.1.10") }, singleLine = true,
            supportingText = { normalized?.let { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name (optional)") }, singleLine = true)
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("User name") }, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(),
            label = { Text(if (existing == null) "Password" else "Password (leave empty to keep)") }, singleLine = true,
            visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = { IconButton({ showPw = !showPw }) { Icon(if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show password") } })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Administrative access")
                Muted("Like \"Reuse my password for privileged tasks\" on the web login. Needed to approve root requests and change things.")
            }
            Switch(admin, { admin = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Notifications")
                Muted("Root requests waiting for approval (with Approve/Deny), Claude sessions down, disk almost full. Checked every 15 minutes.")
            }
            Switch(notify, { notify = it })
        }
        pin?.let { Muted("Trusted certificate (SHA-256):\n$it") }
        error?.let { Banner("Couldn't connect", it, Tone.RED) }
        Button({ connect() }, Modifier.fillMaxWidth(), enabled = !busy && normalized != null && user.isNotBlank() &&
            (password.isNotEmpty() || existing != null)) {
            Text(if (busy) "Connecting…" else if (existing == null) "Connect and save" else "Save")
        }
        if (onDemo != null) {
            TextButton(onDemo, Modifier.fillMaxWidth()) { Text("No server at hand? Try the demo host (debug build)") }
        }
        if (onDeleted != null && existing != null) {
            TextButton({ confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove this host") }
        }
    }

    cert?.let { c ->
        val changed = c.pinned != null
        AlertDialog(
            onDismissRequest = { cert = null },
            title = { Text(if (changed) "Certificate changed!" else "Trust this certificate?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (changed) Banner("The host presented a different certificate than the one you trusted.",
                        "If you didn't reinstall Cockpit or replace its certificate, don't continue: someone may be intercepting the connection.", Tone.RED)
                    else Text("The host uses a certificate that isn't publicly trusted, which is normal for Cockpit's own self-signed certificate. " +
                        "Check that the fingerprint matches the host's (run: sudo openssl x509 -noout -fingerprint -sha256 -in /etc/cockpit/ws-certs.d/0-self-signed.cert).")
                    KeyValue("Issued to", c.certificate.subjectX500Principal.name)
                    KeyValue("Issued by", c.certificate.issuerX500Principal.name)
                    KeyValue("Valid until", DateFormat.getDateInstance().format(c.certificate.notAfter))
                    Text("SHA-256", style = MaterialTheme.typography.labelLarge)
                    Mono(c.sha256)
                }
            },
            confirmButton = {
                Button({ pin = c.sha256; cert = null; connect() },
                    colors = if (changed) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) {
                    Text(if (changed) "Trust the new one" else "Trust and connect")
                }
            },
            dismissButton = { TextButton({ cert = null }) { Text("Cancel") } },
        )
    }
    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text("Remove ${existing.name} and its saved password from this phone?") },
            confirmButton = {
                Button({ confirmDelete = false; store.delete(existing.id); onDeleted?.invoke() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

