package dev.normal99.cockpit

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.normal99.cockpit.ui.AppRoot
import dev.normal99.cockpit.ui.CockpitTheme

class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(false)
    private var startHost by mutableStateOf<String?>(null)
    private var startTab by mutableStateOf<String?>(null)
    /** Bumped when a notification opens a host, so the screen starts over on that host's tab. */
    private var generation by mutableIntStateOf(0)
    private var lastUnlock = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CockpitApp
        readIntent(intent)
        locked = app.store.appLock
        if (Build.VERSION.SDK_INT >= 33 && app.store.hosts.value.isNotEmpty() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            CockpitTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (locked) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Cockpit is locked", style = MaterialTheme.typography.titleLarge)
                            Button(::unlock) { Text("Unlock") }
                        }
                    } else {
                        key(generation) { AppRoot(app, startHost, startTab) }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as CockpitApp
        // Lock again after 2 minutes in the background.
        if (app.store.appLock && System.currentTimeMillis() - lastUnlock > 120_000) locked = true
        if (locked) unlock()
    }

    override fun onStop() {
        super.onStop()
        if (!locked) lastUnlock = System.currentTimeMillis()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (readIntent(intent)) generation++
    }

    private fun readIntent(intent: Intent?): Boolean {
        val host = intent?.getStringExtra(EXTRA_HOST) ?: return false
        startHost = host
        startTab = intent.getStringExtra(EXTRA_TAB)
        return true
    }

    private fun unlock() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                locked = false
                lastUnlock = System.currentTimeMillis()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Cockpit")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_TAB = "tab"
    }
}
