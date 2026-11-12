package dev.normal99.cockpit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.normal99.cockpit.claude.Tone
import dev.normal99.cockpit.cockpit.CockpitException
import dev.normal99.cockpit.cockpit.friendlyMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Chip(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier) {
    val (bg, fg) = toneColors(tone)
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Section(title: String, modifier: Modifier = Modifier, trailing: @Composable (RowScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing?.invoke(this)
            }
            content()
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun Empty(text: String) = Muted(text, Modifier.padding(vertical = 4.dp))

@Composable
fun Mono(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier = modifier, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, label: String? = null) {
    val f = fraction.coerceIn(0f, 1f)
    val color = when {
        f >= 0.9f -> MaterialTheme.colorScheme.error
        f >= 0.8f -> Color(0xFFF0AB00)
        else -> MaterialTheme.colorScheme.primary
    }
    LinearProgressIndicator(
        progress = { f }, color = color, trackColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().height(8.dp).semantics { if (label != null) contentDescription = label },
        drawStopIndicator = {},
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ButtonRow(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

/** Re-composes every 15 s so relative times ("3 min ago") stay fresh, like the web page's ticker. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/* ------------------------------------------------------------------ actions with busy state and a result message */

enum class Kind { PRIMARY, SECONDARY, DANGER, TEXT }

@Stable
class Actions(private val scope: CoroutineScope, private val snackbar: SnackbarHostState, private val onNeedAdmin: (() -> Unit)?) {
    val busy = mutableStateMapOf<String, Boolean>()

    fun isBusy(key: String) = busy[key] == true

    /** Runs [block]; shows its result (or the okText) or the error as a snackbar. */
    fun run(key: String, okText: String? = null, block: suspend () -> String?) {
        if (isBusy(key)) return
        busy[key] = true
        scope.launch {
            try {
                val out = block()
                snackbar.showSnackbar(okText ?: out?.takeIf { it.isNotBlank() } ?: "Done", withDismissAction = true)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val needsAdmin = (e as? CockpitException)?.isAccessDenied == true && onNeedAdmin != null
                val r = snackbar.showSnackbar(
                    e.friendlyMessage(), actionLabel = if (needsAdmin) "Turn on admin" else null,
                    withDismissAction = !needsAdmin, duration = SnackbarDuration.Long,
                )
                if (r == SnackbarResult.ActionPerformed) onNeedAdmin?.invoke()
            } finally {
                busy.remove(key)
            }
        }
    }
}

@Composable
fun rememberActions(snackbar: SnackbarHostState, onNeedAdmin: (() -> Unit)? = null): Actions {
    val scope = rememberCoroutineScope()
    return remember(snackbar) { Actions(scope, snackbar, onNeedAdmin) }
}

@Composable
fun ActionButton(
    text: String,
    busy: Boolean = false,
    kind: Kind = Kind.SECONDARY,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val click = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() }
    val inner: @Composable RowScope.() -> Unit = {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
    val on = enabled && !busy
    when (kind) {
        Kind.PRIMARY -> Button(click, modifier, enabled = on, content = inner)
        Kind.DANGER -> Button(click, modifier, enabled = on, content = inner,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError))
        Kind.SECONDARY -> OutlinedButton(click, modifier, enabled = on, content = inner)
        Kind.TEXT -> TextButton(click, modifier, enabled = on, content = inner)
    }
}

/** A button that asks first, like the web page's inline "are you sure". */
@Composable
fun ConfirmActionButton(
    text: String,
    question: String,
    confirmText: String = "Yes, ${text.lowercase()}",
    busy: Boolean = false,
    kind: Kind = Kind.SECONDARY,
    confirmKind: Kind = Kind.DANGER,
    enabled: Boolean = true,
    onConfirm: () -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    ActionButton(text, busy, kind, enabled) { asking = true }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            text = { Text(question) },
            confirmButton = {
                if (confirmKind == Kind.DANGER) {
                    Button({ asking = false; onConfirm() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(confirmText) }
                } else {
                    FilledTonalButton({ asking = false; onConfirm() }) { Text(confirmText) }
                }
            },
            dismissButton = { TextButton({ asking = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun Banner(title: String, text: String? = null, tone: Tone = Tone.ORANGE, action: (@Composable () -> Unit)? = null) {
    val (bg, fg) = toneColors(tone)
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

@Composable
fun CenteredLoading(text: String = "Loading…") {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(text)
        }
    }
}

