package dev.normal99.cockpit.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.normal99.cockpit.CockpitApp
import dev.normal99.cockpit.MainActivity
import dev.normal99.cockpit.R
import dev.normal99.cockpit.claude.Broker
import dev.normal99.cockpit.claude.ClaudeFiles
import dev.normal99.cockpit.claude.ClaudeRepo
import dev.normal99.cockpit.claude.Status
import dev.normal99.cockpit.claude.Storage
import dev.normal99.cockpit.cockpit.CockpitSession
import dev.normal99.cockpit.cockpit.Endpoint
import dev.normal99.cockpit.cockpit.friendlyMessage
import dev.normal99.cockpit.cockpit.httpClientFor
import dev.normal99.cockpit.cockpit.parseObject
import dev.normal99.cockpit.cockpit.readFile
import dev.normal99.cockpit.data.Host
import java.util.concurrent.TimeUnit

/**
 * Checks each host with notifications on for root requests waiting for approval (and Claude sessions down / disk full)
 * every 15 minutes, the shortest period Android allows for background work. Each request notifies once and has
 * Approve / Deny buttons; on Android 12+ the phone must be unlocked to use them.
 */
class RootRequestWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app get() = applicationContext as CockpitApp

    override suspend fun doWork(): Result {
        for (host in app.store.hosts.value.filter { it.notify }) {
            runCatching { check(host) }
        }
        return Result.success()
    }

    private suspend fun check(host: Host) {
        val pw = app.store.password(host.id) ?: return
        // Reuse the app's live session when there is one; otherwise a short-lived one without admin access (reading only).
        val live = app.connections.get(host).session.takeIf { it.isReady }
        val session = live ?: CockpitSession(Endpoint(host.url), httpClientFor(host.pinnedSha256)).also { it.connect(host.user, pw, admin = false) }
        try {
            val broker = session.readFile(ClaudeFiles.BROKER)?.let { parseObject(it) }?.let(Broker::parse) ?: return
            val seen = app.store.seenRequests(host.id)
            val pending = broker.pending
            for (r in pending.filter { it.id !in seen }) notifyRequest(applicationContext, host, r.id, r.command, r.reason)
            // forget answered ones and clear their notifications
            for (gone in seen - pending.map { it.id }.toSet()) NotificationManagerCompat.from(applicationContext).cancel(notificationId(host, gone))
            app.store.setSeenRequests(host.id, pending.map { it.id }.toSet())

            val status = session.readFile(ClaudeFiles.STATUS)?.let { parseObject(it) }?.let(Status::parse)
            val storage = session.readFile(ClaudeFiles.STORAGE)?.let { parseObject(it) }?.let(Storage::parse)
            health(host, status, storage)
        } finally {
            if (live == null) session.disconnect()
        }
    }

    private fun health(host: Host, status: Status?, storage: Storage?) {
        val problems = buildList {
            val down = status?.sessions?.filter { !it.up || it.rc == "down" }.orEmpty()
            if (status != null && status.sessions.isEmpty()) add("No Claude sessions are running")
            if (down.isNotEmpty()) add("Claude down: " + down.joinToString { it.window })
            storage?.disk?.pct?.let { if (it >= 90) add("Disk $it% full") }
        }
        val key = "health:${host.id}"
        val prefs = applicationContext.getSharedPreferences("notify", Context.MODE_PRIVATE)
        val text = problems.joinToString(" · ")
        if (text == prefs.getString(key, "")) return
        prefs.edit().putString(key, text).apply()
        val nm = NotificationManagerCompat.from(applicationContext)
        val id = ("h" + host.id).hashCode()
        if (problems.isEmpty()) {
            nm.cancel(id)
            return
        }
        if (!canNotify(applicationContext)) return
        post(applicationContext, id, NotificationCompat.Builder(applicationContext, CockpitApp.CHANNEL_HEALTH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(host.name)
            .setContentText(text)
            .setContentIntent(openApp(applicationContext, host.id))
            .setAutoCancel(true)
            .build())
    }

    companion object {
        private const val PERIODIC = "root-requests"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<RootRequestWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Check right away (e.g. when the app goes to the background). */
        fun checkNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<RootRequestWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("$PERIODIC-now", ExistingWorkPolicy.REPLACE, req)
        }

        /** Posts only when allowed; the permission can be revoked at any moment, hence the catch too. */
        @android.annotation.SuppressLint("MissingPermission")
        fun post(context: Context, id: Int, notification: Notification) {
            if (!canNotify(context)) return
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (_: SecurityException) {
            }
        }

        fun notificationId(host: Host, requestId: String) = (host.id + requestId).hashCode()

        fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun openApp(context: Context, hostId: String): PendingIntent = PendingIntent.getActivity(
            context, hostId.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_HOST, hostId).putExtra(MainActivity.EXTRA_TAB, "claude")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun notifyRequest(context: Context, host: Host, id: String, command: String, reason: String, result: String? = null) {
            if (!canNotify(context)) return
            fun action(verb: String, label: String): NotificationCompat.Action {
                val i = Intent(context, RequestActionReceiver::class.java).setAction(verb)
                    .putExtra("host", host.id).putExtra("id", id).putExtra("command", command).putExtra("reason", reason)
                val pi = PendingIntent.getBroadcast(context, (host.id + id + verb).hashCode(), i,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                return NotificationCompat.Action.Builder(0, label, pi).setAuthenticationRequired(true).build()
            }
            val b = NotificationCompat.Builder(context, CockpitApp.CHANNEL_REQUESTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(result ?: "Root request on ${host.name}")
                .setContentText("$ $command")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$ $command\n$reason"))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp(context, host.id))
                .setAutoCancel(result != null)
                .setOnlyAlertOnce(true)
            if (result == null) b.addAction(action(RequestActionReceiver.APPROVE, "Approve")).addAction(action(RequestActionReceiver.DENY, "Deny"))
            post(context, notificationId(host, id), b.build())
        }
    }
}

/** Approve / Deny from the notification: runs claude-ctl through a short admin session, then shows the outcome. */
class RequestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val req = OneTimeWorkRequestBuilder<RequestActionWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(
                "verb" to intent.action, "host" to intent.getStringExtra("host"), "id" to intent.getStringExtra("id"),
                "command" to intent.getStringExtra("command"), "reason" to intent.getStringExtra("reason"),
            ))
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }

    companion object {
        const val APPROVE = "dev.normal99.cockpit.APPROVE"
        const val DENY = "dev.normal99.cockpit.DENY"
    }
}

class RequestActionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo() = androidx.work.ForegroundInfo(
        ("work" + id).hashCode(),
        NotificationCompat.Builder(applicationContext, CockpitApp.CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Answering root request…").build(),
    )

    override suspend fun doWork(): Result {
        val app = applicationContext as CockpitApp
        val host = app.store.host(inputData.getString("host")) ?: return Result.failure()
        val id = inputData.getString("id") ?: return Result.failure()
        val approve = inputData.getString("verb") == RequestActionReceiver.APPROVE
        val command = inputData.getString("command") ?: ""
        val reason = inputData.getString("reason") ?: ""
        val pw = app.store.password(host.id) ?: return Result.failure()
        val live = app.connections.get(host).session.takeIf { it.isReady && app.connections.get(host).hasAdmin }
        val session = live ?: CockpitSession(Endpoint(host.url), httpClientFor(host.pinnedSha256))
        val outcome = try {
            if (live == null) session.connect(host.user, pw, admin = true)
            val repo = ClaudeRepo(session)
            if (approve) repo.approve(id) else repo.deny(id)
            if (approve) "Approved $id" else "Denied $id"
        } catch (e: Throwable) {
            "Couldn't ${if (approve) "approve" else "deny"} $id: ${e.friendlyMessage()}"
        } finally {
            if (live == null) session.disconnect()
        }
        RootRequestWorker.notifyRequest(applicationContext, host, id, command, reason, result = outcome)
        return Result.success()
    }
}
