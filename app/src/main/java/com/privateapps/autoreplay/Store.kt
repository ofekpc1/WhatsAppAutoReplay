package com.privateapps.autoreplay

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Settings(val enabled: Boolean, val message: String, val pkg: String, val auto: Boolean,
                    val excludeContacts: Boolean, val since: Long, val baseline: Long, val rules: ReplyRules = ReplyRules(), val revision: Long = 0,
                    val delivery: DeliveryConfig = DeliveryConfig())
data class Entry(val id: Long, val number: String, val time: Long, val status: String, val detail: String,
                 val message: String, val pkg: String, val auto: Boolean, val manual: Boolean,
                 val mode: DeliveryMode = DeliveryMode.PHONE, val requestId: String = "")
class Store(private val context: Context) : SQLiteOpenHelper(context, "autoreplay.db", null, 2) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT NOT NULL, time INTEGER NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, message TEXT NOT NULL, pkg TEXT NOT NULL, auto INTEGER NOT NULL, manual INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE seen(call_id INTEGER PRIMARY KEY, call_date INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE cooldown(number TEXT PRIMARY KEY, time INTEGER NOT NULL, request_id INTEGER NOT NULL)")
        addDeliveryColumns(db)
    }
    private fun addDeliveryColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE history ADD COLUMN mode TEXT NOT NULL DEFAULT 'PHONE'")
        db.execSQL("ALTER TABLE history ADD COLUMN request_uuid TEXT NOT NULL DEFAULT ''")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if(oldVersion < 2) addDeliveryColumns(db) }
    @Synchronized fun settings() = Settings(prefs.getBoolean("enabled", false), prefs.getString("message", DEFAULT_MESSAGE)!!,
        prefs.getString("pkg", WA)!!, BuildConfig.AUTOMATION_AVAILABLE && prefs.getBoolean("auto", false), prefs.getBoolean("contacts", false),
        prefs.getLong("since", 0), prefs.getLong("baseline", 0), ReplyRules.decode(prefs.getString("rules", null)), prefs.getLong("revision",0), DeliverySettings(context).read())
    @Synchronized fun saveDelivery(config: DeliveryConfig) {
        DeliverySettings(context).save(config)
        val s=settings()
        save(s.message,s.pkg,s.auto,s.excludeContacts,s.rules)
    }
    @Synchronized fun save(message: String, pkg: String, auto: Boolean, contacts: Boolean, rules: ReplyRules = settings().rules) {
        require(rules.valid())
        require(pkg in setOf(WA, BUSINESS))
        writableDatabase.execSQL("UPDATE history SET status='דולג', detail='ההגדרות השתנו; הבקשה בוטלה', manual=0 WHERE status='ממתין' OR manual=1")
        prefs.edit().putLong("revision",prefs.getLong("revision",0)+1).putString("message", message).putString("pkg", pkg).putBoolean("auto", auto).putBoolean("contacts", contacts).putString("rules", rules.encode()).commit()
    }
    @Synchronized fun enable(baseline: Long) {
        prefs.edit().putLong("since", System.currentTimeMillis()).putLong("baseline", baseline).putBoolean("enabled", true).commit()
    }
    @Synchronized fun disable(reason: String) {
        prefs.edit().putBoolean("enabled", false).commit()
        writableDatabase.execSQL("UPDATE history SET status='דולג', detail=?, manual=0 WHERE status='ממתין'", arrayOf(reason))
    }
    @Synchronized fun recover() {
        // Never replay a persisted request after process death; a click may have occurred.
        prefs.edit().putBoolean("enabled", false).commit()
        writableDatabase.execSQL("UPDATE history SET status='נכשל', detail='התהליך הופעל מחדש; אין ניסיון חוזר אוטומטי', manual=0 WHERE status='ממתין'")
    }
    @Synchronized fun hasSeen(id: Long, date: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM seen WHERE call_id=? AND call_date=?", arrayOf(id.toString(), date.toString())).use { it.moveToFirst() }
    @Synchronized fun reserved(number: String): Long? = readableDatabase.rawQuery(
        "SELECT time FROM cooldown WHERE number=?", arrayOf(number)).use { if (it.moveToFirst()) it.getLong(0) else null }
    private fun insert(number: String, status: String, detail: String, config: Settings): Long = writableDatabase.insertOrThrow("history", null,
        ContentValues().apply {
            put("number", number); put("time", System.currentTimeMillis()); put("status", status); put("detail", detail)
            put("message", config.message); put("pkg", config.pkg); put("auto", if(config.auto) 1 else 0); put("manual", 0)
            put("mode",config.delivery.mode.name); put("request_uuid",java.util.UUID.randomUUID().toString())
        })
    @Synchronized fun log(number: String, status: String, detail: String): Long = insert(number,status,detail,settings())
    @Synchronized fun accept(call: CallRecord?, number: String?, reason: String?, config: Settings): Long? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if(settings().revision != config.revision) return null
            if (call != null) {
                val current = settings()
                if (!current.enabled || current.since != config.since || hasSeen(call.id, call.date)) return null
                db.insertWithOnConflict("seen", null, ContentValues().apply { put("call_id",call.id);put("call_date",call.date) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val now = System.currentTimeMillis()
            val duplicate = number?.let { reserved(it) }?.let { now - it < config.rules.cooldownHours * 3_600_000L } ?: false
            val why = reason ?: when {
                config.rules.excludedNumbers().any { Numbers.normalize(it)==number } -> "מספר ברשימת ההחרגות"
                !config.rules.international && number?.startsWith("+972") == false -> "מספר מחוץ לישראל"
                duplicate -> "מניעת הודעה חוזרת לפי זמן ההמתנה שהוגדר"
                else -> null
            }
            val id = insert(number ?: "חסוי / לא תקין", if(why == null) "ממתין" else "דולג", why ?: "ממתין לפתיחת WhatsApp", config)
            if (why == null && number != null) {
                // Reserve before any external side effect, including manual hand-off.
                db.insertWithOnConflict("cooldown", null, ContentValues().apply { put("number",number);put("time",now);put("request_id",id) },SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
            return if(why == null) id else null
        } finally { db.endTransaction() }
    }
    @Synchronized fun update(id: Long, status: String, detail: String, manual: Boolean = false) {
        writableDatabase.update("history", ContentValues().apply {put("status",status);put("detail",detail);put("manual",if(manual) 1 else 0)},"id=?",arrayOf(id.toString()))
    }
    @Synchronized fun claim(id: Long): Boolean {
        val db=writableDatabase
        db.beginTransaction()
        try {
            val e=row(id) ?: return false
            if(e.status!="ממתין") return false
            val now=System.currentTimeMillis()
            val conflict=db.rawQuery("SELECT time,request_id FROM cooldown WHERE number=?",arrayOf(e.number)).use {
                it.moveToFirst() && it.getLong(1)!=id && now-it.getLong(0)<settings().rules.cooldownHours * 3_600_000L
            }
            if(conflict) {
                update(id,"דולג","קיימת בקשה חדשה יותר לאותו מספר בזמן ההמתנה שהוגדר")
                db.setTransactionSuccessful();return false
            }
            db.insertWithOnConflict("cooldown",null,ContentValues().apply {put("number",e.number);put("time",now);put("request_id",id)},SQLiteDatabase.CONFLICT_REPLACE)
            db.update("history",ContentValues().apply {
                put("status","הופעל ניסיון שליחה");put("detail","התחילה פעולה חיצונית; אין אישור מסירה ואין ניסיון חוזר אוטומטי");put("manual",0)
            },"id=? AND status='ממתין'",arrayOf(id.toString()))
            db.setTransactionSuccessful();return true
        } finally {db.endTransaction()}
    }
    private fun readEntry(c: android.database.Cursor) = Entry(c.getLong(0),c.getString(1),c.getLong(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getInt(7)==1,c.getInt(8)==1,DeliveryMode.valueOf(c.getString(9)),c.getString(10))
    @Synchronized fun row(id: Long): Entry? = readableDatabase.rawQuery("SELECT * FROM history WHERE id=?",arrayOf(id.toString())).use { if(it.moveToFirst()) readEntry(it) else null }
    @Synchronized fun history(): List<Entry> = readableDatabase.rawQuery("SELECT * FROM history ORDER BY id DESC LIMIT 200",null).use { c ->
        buildList { while(c.moveToNext()) add(readEntry(c)) }
    }
}
