package com.privateapps.autoreplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

data class ActiveReply(val entry: Entry, val started: Long, val deadline: Long)
object AccessibilityConnection { var connected: Context? = null }
class ReplyDispatcher(private val context: Context, private val store: Store) {
    private val handler=Handler(Looper.getMainLooper())
    var active: ActiveReply? = null
        private set
    // All dispatcher and accessibility operations run on the main looper.
    fun offer(id: Long) {
        val e=store.row(id) ?: return
        if(e.status!="ממתין") return
        if(!Platform.installed(context,e.pkg)) { fail(id,"אפליקציית WhatsApp שנבחרה אינה מותקנת",false);return }
        if(BuildConfig.AUTOMATION_AVAILABLE && e.auto && Platform.unlocked(context) && AccessibilityConnection.connected!=null && active==null) {
            startAuto(e,AccessibilityConnection.connected!!)
        } else {
            store.update(id,"ממתין",if(!Platform.unlocked(context)) "המכשיר נעול; פתחו אותו והמשיכו מהאפליקציה" else "ממתין להמשך מהאפליקציה")
            Notices.attention(context,id,"הודעה ממתינה; פתחו את המכשיר ואת האפליקציה להמשך")
        }
    }
    fun continueFromUser(id: Long, activity: Context, forceManual: Boolean=false) {
        val e=store.row(id) ?: return
        if(e.status!="ממתין" && !(forceManual && e.manual)) return
        if(active!=null) return
        if(!Platform.unlocked(context)) { Notices.attention(context,id,"יש לפתוח את המכשיר לפני המשך השליחה");return }
        if(!Platform.installed(context,e.pkg)) { fail(id,"אפליקציית WhatsApp שנבחרה אינה מותקנת",false);return }
        if(BuildConfig.AUTOMATION_AVAILABLE && e.auto && !forceManual && AccessibilityConnection.connected!=null) startAuto(e,activity)
        else {
            if(e.manual) store.update(id,"ממתין","המשך ידני לפי בחירת המשתמש")
            if(!store.claim(id)) return
            try {
                activity.startActivity(Platform.whatsapp(e))
                store.update(id,"הופעל ניסיון שליחה","נפתחה הודעה מוכנה; יש לבדוק נמען וללחוץ שליחה ידנית. לא ידוע אם נשלחה")
            } catch(_: Exception) { fail(id,"לא ניתן לפתוח את WhatsApp; לא נשלח SMS",false) }
        }
    }
    private fun startAuto(e: Entry, launcher: Context) {
        if(active!=null || store.row(e.id)?.status!="ממתין") return
        val now=SystemClock.uptimeMillis()
        val timeout=store.settings().rules.timeoutSeconds.coerceIn(5,60)*1000L
        active=ActiveReply(e,now,now+timeout)
        try { launcher.startActivity(Platform.whatsapp(e)) }
        catch(_: Exception) { active=null;fail(e.id,"פתיחת WhatsApp נכשלה",true);return }
        handler.postDelayed({
            if(active?.entry?.id==e.id) {
                active=null
                fail(e.id,"לא אומתו השיחה והטקסט בזמן. ייתכן חסם רקע, נמען לא זמין או ממשק לא נתמך; המשיכו ידנית",true)
            }
        },timeout)
    }
    fun consumeForClick(id: Long): Boolean {
        val a=active ?: return false
        if(a.entry.id!=id || SystemClock.uptimeMillis()>=a.deadline || !Platform.unlocked(context)) return false
        active=null // Clear before the external action, even if it throws.
        return store.claim(id)
    }
    fun fail(id: Long, reason: String, manual: Boolean) {
        if(active?.entry?.id==id) active=null
        store.update(id,"נכשל",reason,manual)
        Notices.attention(context,id,reason)
    }
    fun cancel() {
        active?.let { store.update(it.entry.id,"דולג","בקשת השליחה בוטלה") }
        active=null
    }
}
