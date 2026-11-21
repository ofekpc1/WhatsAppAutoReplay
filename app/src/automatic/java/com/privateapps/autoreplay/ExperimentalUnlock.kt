package com.privateapps.autoreplay

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** Single attempt, exact SystemUI IDs only. Unsupported keypads fail closed. */
class ExperimentalUnlock(private val service: AccessibilityService) : UnlockDriver {
    private val handler=Handler(Looper.getMainLooper())
    private val settings=DeliverySettings(service)
    private var entry: Entry?=null
    private var pin=""
    private var index=0
    private var submitted=false
    private var unlocked=false
    private var testing=false
    private var deadline=0L
    override val busy get()=entry!=null
    override val requestId get()=entry?.id
    private var testTask: Runnable?=null
    private val tick=object: Runnable { override fun run() {
        try { step() } catch(_: Exception) { finish(false) }
        if(busy) handler.postDelayed(this,200)
    } }

    fun recover() {
        if(settings.ownsUnlock()) {
            val locked=Build.VERSION.SDK_INT>=28 && runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }.getOrDefault(false)
            if(locked) handler.postDelayed({
                if(service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) settings.finished(false)
                else Notices.attention(service,0,"לא זוהתה נעילה לאחר שחזור; יש לנעול ידנית. פתיחה אוטומטית חסומה")
            },500)
            else Notices.attention(service,0,"ניסיון קודם הופסק; יש לנעול את המכשיר ידנית ולבדוק את ההגדרות")
        }
    }
    override fun test() {
        if(busy || testTask!=null) return
        val revision=service.autoreplay.store.settings().revision
        val task=Runnable {
            testTask=null
            val store=service.autoreplay.store
            if(!busy && store.settings().revision==revision && settings.read().mode==DeliveryMode.UNLOCK) {
                val id=store.log("בדיקת פתיחה בלבד","ממתין","בדיקה ללא הודעת WhatsApp")
                begin(store.row(id)!!,true)
            }
        }
        testTask=task;handler.postDelayed(task,10_000)
    }
    override fun begin(entry: Entry, testOnly: Boolean) {
        if(busy) { service.autoreplay.dispatcher.fail(entry.id,"מתבצעת פעולה אחרת; הבקשה לא תופעל אוטומטית",true);return }
        val secret=runCatching { DeviceSecrets(service).get("pin") }.getOrNull()
        if(Build.VERSION.SDK_INT<28 || !entry.auto || settings.read().mode!=DeliveryMode.UNLOCK ||
            secret==null || !DeliveryValidation.pin(secret) || !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked || !settings.beginAttempt()) {
            service.autoreplay.dispatcher.fail(entry.id,"פתיחה ניסיונית אינה מוכנה או נחסמה לאחר ניסיון קודם; נדרש המשך ידני",true);return
        }
        this.entry=entry;pin=secret;index=0;submitted=false;unlocked=false;testing=testOnly
        deadline=SystemClock.uptimeMillis()+20_000
        try {
            service.startActivity(Intent(service,UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            handler.post(tick)
        } catch(_: Exception) { finish(false) }
    }
    private fun step() {
        val e=entry ?: return
        if(SystemClock.uptimeMillis()>=deadline) { finish(false);return }
        if(unlocked) return // Dispatcher owns the send; deadline still enforces re-lock.
        if(Platform.unlocked(service)) {
            if(index!=pin.length) { finish(false);return }
            unlocked=true;pin="";deadline=SystemClock.uptimeMillis()+65_000
            if(testing) {
                service.autoreplay.store.update(e.id,"בדיקת פתיחה הצליחה","המכשיר נפתח; לא נשלחה הודעה. מתבצעת נעילה מחדש")
                finish(true);return
            }
            service.autoreplay.dispatcher.afterExperimentalUnlock(e.id,service)
            return
        }
        if(submitted) return
        val root=service.rootInActiveWindow ?: return
        if(root.packageName?.toString()!="com.android.systemui") return
        fun one(id: String) = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/$id")
            .filter { it.isVisibleToUser && it.isEnabled && it.packageName?.toString()=="com.android.systemui" }.singleOrNull()
        val field=one("pinEntry") ?: return
        if(!field.isPassword) { finish(false);return }
        val length=field.text?.length ?: return // Cannot establish that the field is empty/current.
        if(length>index || (index==0 && length!=0)) { finish(false);return }
        if(length<index) return // Wait for the previous key; never re-press it.
        if(index<pin.length) {
            val key=one("key${pin[index]}") ?: return
            if(!key.isClickable || !key.refresh() || key.windowId!=root.windowId) return
            index++ // Consume BEFORE click, including an exception or failed accessibility action.
            if(!runCatching { key.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) finish(false)
        } else {
            val enter=one("key_enter") ?: one("keyguard_enter") ?: return
            if(!enter.isClickable || !enter.refresh() || enter.windowId!=root.windowId) return
            submitted=true
            if(!runCatching { enter.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) finish(false)
        }
    }
    override fun finish(success: Boolean) {
        testTask?.let { handler.removeCallbacks(it) };testTask=null
        val e=entry ?: return
        entry=null;pin="";handler.removeCallbacks(tick)
        UnlockActivity.close(e.id)
        val locked=Build.VERSION.SDK_INT>=28 && runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }.getOrDefault(false)
        if(locked) handler.postDelayed({
            if(service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) settings.finished(success && unlocked)
            else Notices.attention(service,e.id,"לא זוהתה נעילה מחדש; נעלו ידנית. המסלול נשאר חסום")
        },500)
        if(!success && service.autoreplay.store.row(e.id)?.status=="ממתין") service.autoreplay.dispatcher.fail(e.id,"הפעולה הניסיונית נעצרה; אין ניסיון קוד חוזר. יש לבדוק ולחדש בהגדרות",false)
        if(!locked) Notices.attention(service,e.id,"לא התקבל אישור לפעולת הנעילה; נעלו את המכשיר ידנית. המסלול הניסיוני נחסם")
    }
}
