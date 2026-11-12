package dev.normal99.cockpit.ui

import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Same wording as the web page (claude.js): "just now", "5 min ago", "3 h ago", "2 d ago". */
fun ago(ms: Long?, now: Long = System.currentTimeMillis()): String {
    if (ms == null || ms <= 0) return "—"
    val s = ((now - ms) / 1000.0).coerceAtLeast(0.0)
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${(s / 60).roundToInt()} min ago"
        s < 86400 -> "${(s / 3600).roundToInt()} h ago"
        else -> "${(s / 86400).roundToInt()} d ago"
    }
}

fun duration(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0)
    return when {
        s < 3600 -> "${(s / 60).roundToInt().coerceAtLeast(1)} min"
        s < 172800 -> "%.1f h".format(s / 3600)
        else -> "${(s / 86400).roundToInt()} d"
    }
}

fun bytes(n: Long?): String {
    if (n == null) return "—"
    val u = listOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return if (i == 0) "$n B" else "%.1f %s".format(v, u[i])
}

fun rate(bps: Double) = bytes(bps.toLong()) + "/s"

fun fullTime(ms: Long?): String = if (ms == null) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
fun shortTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(ms))
