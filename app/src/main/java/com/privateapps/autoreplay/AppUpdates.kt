package com.privateapps.autoreplay

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

/** Uses its own public download channel. Never reads/sends contacts, messages or call history. */
class AppUpdates(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val mayInstall: () -> Boolean,
    private val client: AppUpdateClient = AppUpdateClient(activity)
) : AutoCloseable {
    var status by mutableStateOf("בדיקת עדכונים זמינה")
        private set
    var release by mutableStateOf(client.cached())
        private set
    var busy by mutableStateOf(false)
        private set
    var confirm by mutableStateOf(false)
    var permissionRequired by mutableStateOf(false)
    private var foreground = false
    private var checked = false
    val newer: Boolean get() = release?.let { it.code() > BuildConfig.VERSION_CODE } == true
    val ready: Boolean get() = release?.let { client.downloaded(it) } == true

    fun resumed() { foreground = true; if (!checked) check() }
    fun paused() { foreground = false; confirm = false; permissionRequired = false }
    fun check() {
        if (busy) return
        checked = true; busy = true; status = "בודק אם קיימת גרסה חדשה…"
        scope.launch {
            try {
                release = withContext(Dispatchers.IO) { client.check() }
                status = if (newer) "זמינה גרסה ${release!!.name()}" else "האפליקציה מעודכנת"
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { status = "בדיקת העדכון לא הצליחה. אפשר להמשיך להשתמש באפליקציה ולנסות שוב."
            } finally { busy = false }
        }
    }
    fun download() {
        val target = release ?: return
        if (busy || !newer) return
        confirm = false; busy = true; status = "מוריד עדכון…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) { client.download(target) { percent ->
                    scope.launch { status = "מוריד עדכון: $percent%" }
                } }
                // Downloading never opens the installer or interrupts a reply.
                status = "העדכון מוכן. שמרו שינויים והשהו את המענה לפני לחיצה על התקנה."
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { status = "ההורדה לא הושלמה או שהקובץ לא תקין. אפשר לנסות שוב."
            } finally { busy = false }
        }
    }
    fun install() {
        val target = release ?: return
        confirm = false
        if (busy || !foreground || !newer || !mayInstall()) {
            status = "יש לשמור שינויים ולהשהות את המענה. אין להתקין בזמן טיפול בהודעה."; return
        }
        busy = true; status = "מאמת גרסה וחתימה…"
        scope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { client.verifiedInstaller(target) }
                // A call or navigation may have happened while validation ran.
                if (!foreground || !mayInstall()) { status = "הקובץ מוכן. חזרו להתקנה לאחר השהיית המענה."; return@launch }
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    permissionRequired = true; status = "נדרש אישור להתקנת עדכון ממקור זה"; return@launch
                }
                activity.startActivity(installerIntent(activity, apk))
                status = "לאחר ההתקנה פתחו את האפליקציה והפעילו שוב את המענה. אם ביטלתם, אפשר לנסות שוב."
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { status = "לא ניתן להתקין: בדיקת הקובץ או החתימה נכשלה, או שהמערכת חסמה התקנה."
            } finally { busy = false }
        }
    }
    fun permissionSettings() {
        permissionRequired = false
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
        }.onFailure { status = "מסך הרשאת ההתקנה אינו זמין במכשיר זה" }
        // No automatic installation on resume: the user explicitly presses Install again.
    }
    override fun close() { client.close() }

    companion object {
        fun installerIntent(context: Context, apk: File): Intent = installerIntent(
            FileProvider.getUriForFile(context, context.packageName + ".updates", apk))
        fun installerIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("עדכון אפליקציה", uri)
        }
    }
}

@Composable
fun UpdateCard(updates: AppUpdates, installationAllowed: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("גרסה ${BuildConfig.VERSION_NAME} · בנייה ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.titleMedium)
            Text(updates.status)
            if (updates.newer) {
                Text(updates.release?.notes().orEmpty())
                Button(onClick = { updates.confirm = true }, enabled = !updates.busy && (!updates.ready || installationAllowed)) {
                    Text(if (updates.ready) "התקנת העדכון" else "הורד עדכון")
                }
                if (updates.ready && !installationAllowed) Text("שמרו שינויים והשהו את המענה כדי להתקין.")
            }
            TextButton(onClick = updates::check, enabled = !updates.busy) { Text("בדוק עדכון") }
        }
    }
    if (updates.confirm) AlertDialog(
        onDismissRequest = { updates.confirm = false }, title = { Text("עדכון לגרסה ${updates.release?.name()}") },
        text = { Text("ההגדרות, התבניות וההיסטוריה נשמרות. Android יבקש אישור התקנה. לאחר העדכון יש לפתוח את האפליקציה ולהפעיל שוב את המענה. הודעות ישנות לא יישלחו מחדש אוטומטית.") },
        confirmButton = { TextButton(onClick = { if (updates.ready) updates.install() else updates.download() }) { Text(if (updates.ready) "המשך להתקנה" else "הורד") } },
        dismissButton = { TextButton(onClick = { updates.confirm = false }) { Text("אחר כך") } })
    if (updates.permissionRequired) AlertDialog(
        onDismissRequest = { updates.permissionRequired = false }, title = { Text("אישור התקנה") },
        text = { Text("אפשרו התקנה ממקור זה במסך Android, חזרו לאפליקציה ולחצו שוב על התקנת העדכון.") },
        confirmButton = { TextButton(onClick = updates::permissionSettings) { Text("פתח הגדרות") } },
        dismissButton = { TextButton(onClick = { updates.permissionRequired = false }) { Text("אחר כך") } })
}
