package com.privateapps.autoreplay

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ReplyAccessibilityService : AccessibilityService() {
    private val unlock by lazy { ExperimentalUnlock(this) }
    override fun onServiceConnected() { AccessibilityConnection.connected=this;UnlockConnection.driver=unlock;unlock.recover() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val dispatch=autoreplay.dispatcher
        val a=dispatch.active ?: return
        val e=a.entry
        if(event==null || event.packageName?.toString()!=e.pkg || event.eventTime<a.started) return
        if(!Platform.unlocked(this)) { dispatch.fail(e.id,"המכשיר ננעל במהלך הפעולה; המשך ידני בלבד",true);return }
        if(SystemClock.uptimeMillis()>=a.deadline) return
        val root=rootInActiveWindow ?: return
        if(root.packageName?.toString()!=e.pkg) return
        // Exact view IDs only. No coordinate taps, fuzzy text, contact-name matching, or global actions.
        fun nodes(id: String) = root.findAccessibilityNodeInfosByViewId("${e.pkg}:id/$id")
            .filter { it.isVisibleToUser && it.packageName?.toString()==e.pkg }
        // A title/contact name can be user-defined (even a group named as a phone number).
        // It is NEVER accepted as identity evidence. Require a distinct phone-number field.
        // Builds of WhatsApp that don't expose this field deliberately fall back to manual.
        val recipients=nodes("conversation_contact_number")
        val editors=nodes("entry").filter { it.isEditable }
        val sends=nodes("send").filter { it.isClickable }
        val proof=UiProof(e.pkg,recipients.singleOrNull()?.text?.toString(),editors.singleOrNull()?.text?.toString(),
            editors.size,recipients.size,sends.size,sends.singleOrNull()?.isEnabled==true,!Platform.unlocked(this))
        if(!SendGuard.permits(e.pkg,e.number,e.message,a.deadline,SystemClock.uptimeMillis(),false,proof)) return
        // Re-read immediately before claiming; never reuse a stale button after a window transition.
        val button=sends.single()
        if(!button.refresh() || !button.isVisibleToUser || !button.isEnabled || button.windowId!=root.windowId) return
        if(!dispatch.consumeForClick(e.id)) return
        try {
            val clicked=button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            autoreplay.store.update(e.id,"הופעל ניסיון שליחה",if(clicked) "בוצעה לחיצה אחת על שליחה לאחר אימות; אין אישור מסירה"
                else "פעולת הלחיצה לא אושרה; מצב השליחה אינו ודאי. לא יבוצע ניסיון חוזר")
        } catch(_: Exception) { autoreplay.store.update(e.id,"הופעל ניסיון שליחה","תקלה בזמן הלחיצה; מצב לא ודאי, ללא ניסיון חוזר") }
        finally { dispatch.localAttemptFinished(e.id) }
    }
    override fun onInterrupt() { unlock.finish(false);autoreplay.dispatcher.active?.let { autoreplay.dispatcher.fail(it.entry.id,"שירות הנגישות הופרע; נדרש המשך ידני",true) } }
    override fun onDestroy() { if(AccessibilityConnection.connected===this) AccessibilityConnection.connected=null;onInterrupt();if(UnlockConnection.driver===unlock) UnlockConnection.driver=null;super.onDestroy() }
}
