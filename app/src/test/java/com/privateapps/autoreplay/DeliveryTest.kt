package com.privateapps.autoreplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class DeliveryTest {
    private val app get()=RuntimeEnvironment.getApplication() as AutoReplayApp
    private val api=DeliveryConfig(DeliveryMode.API,"123456789","missed_call","he","v27.0")
    private class FakeConnection(val status: Int=200, val response: String="{\"messages\":[{\"id\":\"wamid.test\"}]}", val loseResponse: Boolean=false) : HttpsURLConnection(URL("https://graph.facebook.com")) {
        val written=ByteArrayOutputStream()
        var disconnected=false
        override fun connect() = Unit
        override fun disconnect() {disconnected=true}
        override fun usingProxy()=false
        override fun getCipherSuite()="test"
        override fun getLocalCertificates(): Array<Certificate>?=null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream()=written
        override fun getResponseCode(): Int { if(loseResponse) throw IOException("lost");return status }
        override fun getInputStream()=ByteArrayInputStream(response.toByteArray())
    }
    @Test fun sendsApprovedTemplateOnlyToFixedHttpsHost() {
        val fake=FakeConnection();var opened: URL?=null
        val result=CloudClient { opened=it;fake }.send(api.copy(parameters="One\nTwo"),"+972502345678","fake-test-token")
        assertEquals("https://graph.facebook.com/v27.0/123456789/messages",opened.toString())
        assertFalse(fake.instanceFollowRedirects)
        assertEquals("POST",fake.requestMethod)
        val body=JSONObject(fake.written.toString("UTF-8"))
        assertEquals("972502345678",body.getString("to"))
        assertEquals("template",body.getString("type"))
        assertEquals(2,body.getJSONObject("template").getJSONArray("components").getJSONObject(0).getJSONArray("parameters").length())
        assertEquals("התקבל ב־Meta",result.state)
        assertTrue(result.detail.contains("אין אישור מסירה"))
        assertTrue(fake.disconnected)
    }
    @Test fun lostResponseNeverRetries() {
        var count=0
        val fake=FakeConnection(loseResponse=true)
        val result=CloudClient { count++;fake }.send(api,"+972502345678","fake-test-token")
        assertTrue(fake.written.size()>0)
        assertEquals(1,count);assertEquals("לא ודאי",result.state)
    }
    @Test fun expiredAuthorizationIsReportedWithoutExposingToken() {
        val result=CloudClient { FakeConnection(401) }.send(api,"+972502345678","private-token")
        assertEquals("נכשל",result.state);assertTrue(result.detail.contains("פגה"));assertFalse(result.detail.contains("private-token"))
    }
    @Test fun redirectIsNotFollowed() {
        var count=0
        val result=CloudClient { count++;FakeConnection(307) }.send(api,"+972502345678","token")
        assertEquals(1,count);assertEquals("לא ודאי",result.state)
    }
    @Test fun successWithoutMessageIdRemainsUncertain() {
        assertEquals("לא ודאי",CloudClient { FakeConnection(response="{}") }.send(api,"+972502345678","token").state)
    }
    @Test fun serverErrorDoesNotRetryOrExposeRawBody() {
        var count=0
        val result=CloudClient { count++;FakeConnection(503,"private-error") }.send(api,"+972502345678","token")
        assertEquals(1,count);assertEquals("לא ודאי",result.state);assertFalse(result.detail.contains("private-error"))
    }
    @Test fun configurationCannotInjectDifferentHostOrHeader() {
        assertFalse(DeliveryValidation.api(api.copy(phoneId="../other")))
        assertFalse(DeliveryValidation.api(api.copy(version="https://evil.example")))
        var count=0
        CloudClient { count++;FakeConnection() }.send(api,"+972502345678","token\r\nX: y")
        assertEquals(0,count)
    }
    @Test fun missingTokenDoesNotFallBackToPhoneOnLockedDevice() {
        app.store.saveDelivery(api)
        shadowOf(app.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        val id=app.store.accept(null,"+972502345678",null,app.store.settings())!!
        app.dispatcher.offer(id)
        assertEquals("נכשל",app.store.row(id)!!.status)
        assertEquals(DeliveryMode.API,app.store.row(id)!!.mode)
        assertNull(shadowOf(app).nextStartedActivity)
        assertFalse(app.store.row(id)!!.manual)
    }
    @Test fun switchingRouteCancelsPendingAndDoesNotReopenAnAttempt() {
        val pending=app.store.accept(null,"+972502345678",null,app.store.settings())!!
        val started=app.store.accept(null,"+972502345679",null,app.store.settings())!!
        assertTrue(app.store.claim(started))
        app.store.saveDelivery(api)
        assertEquals("דולג",app.store.row(pending)!!.status)
        assertEquals(DeliveryMode.PHONE,app.store.row(started)!!.mode)
        assertFalse(app.store.claim(started))
    }
    @Test fun newRequestsHaveDistinctPersistentIds() {
        app.store.saveDelivery(api)
        val a=app.store.accept(null,"+972502345678",null,app.store.settings())!!
        val b=app.store.accept(null,"+972502345679",null,app.store.settings())!!
        val first=app.store.row(a)!!;val second=app.store.row(b)!!
        assertNotEquals(first.requestId,second.requestId)
        java.util.UUID.fromString(first.requestId)
        assertEquals(first.requestId,Store(app).use { it.row(a)!!.requestId })
    }
    @Test fun unlockFailureLatchSurvivesRecreationAndAllowsOnlyOneAttempt() {
        val settings=DeliverySettings(app)
        assertTrue(settings.blocked());assertFalse(settings.beginAttempt())
        settings.arm();assertTrue(settings.beginAttempt());assertFalse(settings.beginAttempt())
        val reopened=DeliverySettings(app)
        assertTrue(reopened.blocked());assertTrue(reopened.ownsUnlock())
        reopened.finished(false);assertFalse(reopened.beginAttempt())
        reopened.arm();assertTrue(reopened.beginAttempt());reopened.finished(true)
        assertFalse(reopened.blocked());assertFalse(reopened.ownsUnlock())
    }
    @Test fun onlyNumericPinsWithBoundedLengthAccepted() {
        assertTrue(DeliveryValidation.pin("012345"));assertFalse(DeliveryValidation.pin("123"))
        assertFalse(DeliveryValidation.pin("1234\n"));assertFalse(DeliveryValidation.pin("１２３４"))
        assertFalse(DeliveryValidation.pin("1234567890123"))
    }
    @Test fun concurrentClaimsPermitOnlyOneExternalAttempt() {
        val id=app.store.accept(null,"+972502345678",null,app.store.settings())!!
        val pool=java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val results=(1..12).map { pool.submit<Boolean> { app.store.claim(id) } }.map {it.get()}
            assertEquals(1,results.count {it})
        } finally { pool.shutdownNow() }
    }
    @Test fun versionOneHistorySurvivesMigration() {
        app.store.close();app.deleteDatabase("autoreplay.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath("autoreplay.db"),null).use { db ->
            db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, number TEXT NOT NULL, time INTEGER NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL, message TEXT NOT NULL, pkg TEXT NOT NULL, auto INTEGER NOT NULL, manual INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE seen(call_id INTEGER PRIMARY KEY, call_date INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE cooldown(number TEXT PRIMARY KEY, time INTEGER NOT NULL, request_id INTEGER NOT NULL)")
            db.execSQL("INSERT INTO history(number,time,status,detail,message,pkg,auto,manual) VALUES('+972502345678',1,'old','detail','message','com.whatsapp',0,0)")
            db.version=1
        }
        Store(app).use {
            val old=it.row(1)!!
            assertEquals("old",old.status);assertEquals("message",old.message)
            assertEquals(DeliveryMode.PHONE,old.mode);assertEquals("",old.requestId)
        }
    }
    @Test fun pendingRequestOutsideHistoryDisplayLimitStillCanBeClaimedOnce() {
        val id=app.store.accept(null,"+972502345678",null,app.store.settings())!!
        repeat(205) { app.store.log("test","דולג","test") }
        assertEquals(200,app.store.history().size)
        assertTrue(app.store.claim(id));assertFalse(app.store.claim(id))
    }
}
