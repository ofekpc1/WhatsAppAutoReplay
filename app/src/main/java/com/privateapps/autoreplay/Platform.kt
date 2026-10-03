package com.privateapps.autoreplay

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Platform {
    fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c,p)==PackageManager.PERMISSION_GRANTED
    fun notifications(c: Context) = (Build.VERSION.SDK_INT<33 || granted(c,Manifest.permission.POST_NOTIFICATIONS)) &&
        NotificationManagerCompat.from(c).areNotificationsEnabled() && listOf("monitor","action").all {
            c.getSystemService(NotificationManager::class.java).getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    fun unlocked(c: Context) = !c.getSystemService(KeyguardManager::class.java).isKeyguardLocked && c.getSystemService(PowerManager::class.java).isInteractive
    fun installed(c: Context, pkg: String) = try { c.packageManager.getPackageInfo(pkg,0); true } catch(_: PackageManager.NameNotFoundException) { false }
    fun accountPermission(c: Context) = Build.VERSION.SDK_INT<31 || granted(c,Manifest.permission.READ_PHONE_NUMBERS)
    fun isCellular(c: Context, component: String?, accountId: String?): Boolean {
        if(component.isNullOrBlank() || accountId.isNullOrBlank()) return false
        val name=ComponentName.unflattenFromString(component) ?: return false
        val account=c.getSystemService(TelecomManager::class.java)?.getPhoneAccount(PhoneAccountHandle(name,accountId))
        return account?.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)==true
    }
    fun inContacts(c: Context, number: String): Boolean {
        val uri=Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return c.contentResolver.query(uri,arrayOf(ContactsContract.PhoneLookup._ID),null,null,null)?.use { it.moveToFirst() }
            ?: throw IllegalStateException("לא ניתן לבדוק אנשי קשר")
    }
    fun whatsapp(entry: Entry) = Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("https").authority("wa.me")
        .appendPath(entry.number.removePrefix("+")).appendQueryParameter("text",entry.message).build())
        .setPackage(entry.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
object Notices {
    private const val MONITOR = "monitor"
    private const val ACTION = "action"
    fun create(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(MONITOR,"ניטור שיחות",NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(ACTION,"הודעות ממתינות ותקלות",NotificationManager.IMPORTANCE_DEFAULT)))
    }
    private fun open(c: Context) = PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun monitor(c: Context): Notification = NotificationCompat.Builder(c,MONITOR).setSmallIcon(R.drawable.ic_autoreplay)
        .setContentTitle("Whatsapp Auto replay פעיל").setContentText("ממתין לשיחות חדשות שלא נענו")
        .setContentIntent(open(c)).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .addAction(0,"כיבוי",PendingIntent.getService(c,1,Intent(c,CallMonitorService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)).build()
    fun attention(c: Context, id: Long, text: String) {
        if(!Platform.notifications(c)) return
        try { NotificationManagerCompat.from(c).notify(1000+(id%100000).toInt(),NotificationCompat.Builder(c,ACTION)
            .setSmallIcon(R.drawable.ic_autoreplay).setContentTitle("Whatsapp Auto replay")
            .setContentText(text).setContentIntent(open(c)).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()) } catch(_: SecurityException) { }
    }
}
