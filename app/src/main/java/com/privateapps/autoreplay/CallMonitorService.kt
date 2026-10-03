package com.privateapps.autoreplay

import android.Manifest
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.os.*
import android.provider.CallLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class CallMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var started=false
    private val floating by lazy {FloatingReplies(this)}
    private val observer = object: ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { changes.trySend(Unit) } }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="STOP" || !autoreplay.store.settings().enabled) { stopMonitoring(); return START_NOT_STICKY }
        try {
            startForeground(10,Notices.monitor(this))
            if(!started) {
                started=true
                scope.launch {while(isActive) {floating.refresh();delay(1000)}}
                contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI,true,observer)
                scope.launch { for(signal in changes) { delay(1200); scan() } }
                scope.launch { while(isActive) { changes.trySend(Unit); delay(15_000) } }
            }
        } catch(e: Exception) { fail("לא ניתן להפעיל ניטור: ${e.javaClass.simpleName}") }
        return START_NOT_STICKY
    }
    private suspend fun scan() {
        val config=autoreplay.store.settings()
        if(!config.enabled) return
        if(!Platform.granted(this,Manifest.permission.READ_CALL_LOG)) { fail("הרשאת יומן שיחות חסרה; יש להפעיל מחדש לאחר אישור"); return }
        if(!Platform.accountPermission(this)) { fail("חסרה הרשאה לאימות חשבון SIM; הניטור הופסק");return }
        if(!Platform.notifications(this)) { fail("התראות חסומות; הניטור הופסק"); return }
        try {
            val ids=withContext(Dispatchers.IO) {
                val accepted=mutableListOf<Long>()
                contentResolver.query(CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls._ID,CallLog.Calls.DATE,CallLog.Calls.TYPE,CallLog.Calls.NUMBER,CallLog.Calls.NUMBER_PRESENTATION,CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,CallLog.Calls.PHONE_ACCOUNT_ID,CallLog.Calls.DURATION),
                    "${CallLog.Calls._ID}>? AND ${CallLog.Calls.DATE}>?",arrayOf(config.baseline.toString(),config.since.toString()),"${CallLog.Calls._ID} ASC")?.use { c ->
                    while(c.moveToNext()) {
                        val call=CallRecord(c.getLong(0),c.getLong(1),c.getInt(2),c.getString(3),c.getInt(4),c.getLong(7))
                        if(autoreplay.store.hasSeen(call.id,call.date)) continue
                        val number=Numbers.normalize(call.number)
                        val cellular=Platform.isCellular(this@CallMonitorService,c.getString(5),c.getString(6))
                        val contactsGranted=Platform.granted(this@CallMonitorService,Manifest.permission.READ_CONTACTS)
                        val contact=if(config.excludeContacts && contactsGranted && number!=null) Platform.inContacts(this@CallMonitorService,number) else false
                        val result=Policy.eligible(call,config.since,config.baseline,false,true,config.excludeContacts,contactsGranted,contact,
                            number?.let { autoreplay.store.reserved(it) },System.currentTimeMillis(),config.rules)
                        val id=autoreplay.store.accept(call,result.number,if(!cellular) "לא זוהה חשבון SIM סלולרי; שיחת VoIP או חשבון לא זמין" else result.reason,config.copy(message=config.rules.message(call,config.message)))
                        if(id!=null) accepted.add(id)
                    }
                } ?: throw IllegalStateException("יומן השיחות לא זמין")
                accepted
            }
            for(id in ids) autoreplay.dispatcher.offer(id)
        } catch(_: SecurityException) { fail("המערכת חסמה גישה ליומן או לאנשי קשר; לא נשלחו הודעות") }
          catch(_: Exception) { fail("קריאת יומן השיחות נכשלה; הפעילו מחדש לאחר בדיקת ההרשאות") }
    }
    private fun fail(text: String) { val id=autoreplay.store.log("מערכת","נכשל",text);Notices.attention(this,id,text);stopMonitoring() }
    private fun stopMonitoring() { autoreplay.store.disable("הניטור כובה");autoreplay.dispatcher.cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
    override fun onDestroy() {
        floating.close()
        contentResolver.unregisterContentObserver(observer)
        scope.cancel()
        autoreplay.store.disable("הניטור נעצר; יש להפעיל מחדש")
        autoreplay.dispatcher.cancel()
        super.onDestroy()
    }
    companion object {
        fun baseline(c: android.content.Context): Long = c.contentResolver.query(CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID),null,null,"${CallLog.Calls._ID} DESC")?.use { if(it.moveToFirst()) it.getLong(0) else 0L }
            ?: throw IllegalStateException("יומן השיחות לא זמין")
    }
}
