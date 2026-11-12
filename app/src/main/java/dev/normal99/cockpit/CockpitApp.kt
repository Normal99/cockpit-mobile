package dev.normal99.cockpit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.normal99.cockpit.data.Connections
import dev.normal99.cockpit.data.HostStore
import dev.normal99.cockpit.notify.RootRequestWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CockpitApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var store: HostStore
        private set
    lateinit var connections: Connections
        private set

    override fun onCreate() {
        super.onCreate()
        store = HostStore(this)
        connections = Connections(store, scope)
        if (Demo.available && store.hosts.value.any { it.url == Demo.URL }) Demo.ensureStarted()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REQUESTS, "Root requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Commands waiting for your approval on the Claude page"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HEALTH, "Host health", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Claude sessions down, disk almost full"
            },
        )
        RootRequestWorker.schedule(this)
    }

    companion object {
        const val CHANNEL_REQUESTS = "requests"
        const val CHANNEL_HEALTH = "health"
    }
}
