package com.privateapps.autoreplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class StoreTest {
    @Test fun editingWhileEnabledRejectsStaleScanAndKeepsMonitoring() {
        store.enable(100)
        val old=store.settings()
        store.save("new message",WA,false,false)
        assertTrue(store.settings().enabled)
        val call=CallRecord(101,System.currentTimeMillis()+1,3,"0502345678",1)
        assertNull(store.accept(call,"+972502345678",null,old))
        assertFalse(store.hasSeen(call.id,call.date))
        val id=store.accept(call,"+972502345678",null,store.settings())!!
        assertEquals("new message",store.row(id)!!.message)
    }
    @Test fun templatesPersistEditAndDelete() {
        val templates=Templates(app)
        val list=listOf(MessageTemplate("בדיקה","תוכן\nשורה שנייה"))
        templates.save(list)
        assertEquals(list,Templates(app).load())
        templates.save(emptyList())
        assertTrue(Templates(app).load().isEmpty())
        assertFalse(templates.floating)
        templates.floating=true
        assertTrue(Templates(app).floating)
    }
    @Test fun floatingWithoutPermissionDoesNotCrash() {
        Templates(app).floating=true
        val overlay=FloatingReplies(app)
        overlay.refresh();overlay.close();overlay.close()
    }
    @Test fun templatePickerOpensWithoutPermissions() {
        org.robolectric.Robolectric.buildActivity(TemplateActivity::class.java).setup().get().finish()
    }
    @Test fun newRulesPersistAndRoundTrip() {
        val r=ReplyRules(incoming=true,outgoing=true,cooldownHours=720,excluded="0502345678",international=false,timeoutSeconds=30,outsideEnabled=true)
        store.save(DEFAULT_MESSAGE,WA,false,false,r)
        assertEquals(r,store.settings().rules)
        assertEquals(r,ReplyRules.decode(r.encode()))
    }
    @Test fun zeroCooldownStillDeduplicatesCallAndClaims() {
        store.save(DEFAULT_MESSAGE,WA,false,false,ReplyRules(cooldownHours=0))
        store.enable(100)
        val c=CallRecord(101,System.currentTimeMillis()+1,3,"0502345678",1)
        val id=store.accept(c,"+972502345678",null,store.settings())!!
        assertNull(store.accept(c,"+972502345678",null,store.settings()))
        assertTrue(store.claim(id));assertFalse(store.claim(id))
    }
    @Test fun manualTestRespectsExclusions() {
        store.save(DEFAULT_MESSAGE,WA,false,false,ReplyRules(excluded="0502345678"))
        assertNull(store.accept(null,"+972502345678",null,store.settings()))
        assertEquals("מספר ברשימת ההחרגות",store.history().first().detail)
    }
    private val app get()=RuntimeEnvironment.getApplication() as AutoReplayApp
    private val store get()=app.store
    private fun request(number:String="+972502345678")=store.accept(null,number,null,store.settings())!!
    @Test fun reservePreventsDuplicateBeforeAnyClick() {
        request()
        assertNull(store.accept(null,"+972502345678",null,store.settings()))
        assertEquals(1,store.history().count{it.status=="ממתין"})
        assertEquals(1,store.history().count{it.status=="דולג"})
    }
    @Test fun claimCanSucceedOnlyOnce() {
        val id=request()
        assertTrue(store.claim(id));assertFalse(store.claim(id))
        assertEquals("הופעל ניסיון שליחה",store.row(id)!!.status)
    }
    @Test fun rebootRecoveryDoesNotReplayPending() {
        val id=request();store.enable(10);store.recover()
        assertFalse(store.settings().enabled)
        assertEquals("נכשל",store.row(id)!!.status)
        assertFalse(store.row(id)!!.manual)
        assertFalse(store.claim(id))
        assertNotNull(store.reserved("+972502345678"))
    }
    @Test fun uncertainAttemptRemainsAttemptOnRecovery() {
        val id=request();store.claim(id);store.recover()
        assertEquals("הופעל ניסיון שליחה",store.row(id)!!.status)
        assertFalse(store.claim(id))
    }
    @Test fun disableCancelsQueuedRequests() {
        val id=request();store.disable("כובה")
        assertEquals("דולג",store.row(id)!!.status);assertFalse(store.claim(id))
    }
    @Test fun repeatedCallLogNotificationIsIdempotent() {
        store.enable(100);val s=store.settings()
        val c=CallRecord(101,System.currentTimeMillis(),3,"0502345678",1)
        assertNotNull(store.accept(c,"+972502345678",null,s))
        assertNull(store.accept(c,"+972502345678",null,s))
        assertEquals(1,store.history().size)
    }
    @Test fun scanFromPreviousActivationCannotQueue() {
        store.enable(100);val s=store.settings();store.disable("stop")
        assertNull(store.accept(CallRecord(101,System.currentTimeMillis(),3,"0502345678",1),"+972502345678",null,s))
    }
    @Test fun settingsAndHistorySurviveStoreReopen() {
        store.save("בדיקה",BUSINESS,true,true);val id=request()
        val reopened=Store(app)
        assertEquals("בדיקה",reopened.settings().message);assertEquals(BUSINESS,reopened.settings().pkg)
        assertEquals(id,reopened.row(id)!!.id);reopened.close()
    }
    @Test fun missingWhatsappFailsWithoutExternalLaunch() {
        assertFalse(Platform.installed(app,WA))
        val id=request();app.dispatcher.offer(id)
        assertEquals("נכשל",store.row(id)!!.status)
        assertNull(app.dispatcher.active)
        assertFalse(store.row(id)!!.manual)
    }
    @Test fun failedRequestDoesNotAutomaticallyRetry() {
        val id=request();app.dispatcher.fail(id,"timeout",true)
        app.dispatcher.offer(id)
        assertEquals("נכשל",store.row(id)!!.status)
        assertNull(app.dispatcher.active)
    }
    @Test fun lockedDeviceQueuesWithoutLaunchingWhatsapp() {
        shadowOf(app.packageManager).installPackage(android.content.pm.PackageInfo().apply { packageName=WA })
        shadowOf(app.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        store.save(DEFAULT_MESSAGE,WA,true,false)
        val id=request();app.dispatcher.offer(id)
        assertEquals("ממתין",store.row(id)!!.status)
        assertTrue(store.row(id)!!.detail.contains("נעול"))
        assertNull(app.dispatcher.active)
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun screenOffAlsoPreventsLaunch() {
        shadowOf(app.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(false)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(false)
        assertFalse(Platform.unlocked(app))
    }
    @Test fun simAccountAcceptedButVoipRejected() {
        val component=android.content.ComponentName("com.android.phone","TestConnectionService")
        val telecom=app.getSystemService(android.telecom.TelecomManager::class.java)
        val sim=android.telecom.PhoneAccountHandle(component,"sim")
        val voip=android.telecom.PhoneAccountHandle(component,"voip")
        telecom.registerPhoneAccount(android.telecom.PhoneAccount.builder(sim,"SIM").setCapabilities(android.telecom.PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION).build())
        telecom.registerPhoneAccount(android.telecom.PhoneAccount.builder(voip,"VoIP").setCapabilities(android.telecom.PhoneAccount.CAPABILITY_SELF_MANAGED).build())
        assertTrue(Platform.isCellular(app,component.flattenToString(),"sim"))
        assertFalse(Platform.isCellular(app,component.flattenToString(),"voip"))
        assertFalse(Platform.isCellular(app,null,null))
    }
    @Test fun settingsChangeCancelsOldPackageRequests() {
        val id=request();store.save(DEFAULT_MESSAGE,BUSINESS,true,false)
        assertEquals("דולג",store.row(id)!!.status)
        assertFalse(store.claim(id))
    }
    @Test fun mainScreenStartsWithoutPermissions() {
        val controller=org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get().findViewById<android.view.View>(android.R.id.content))
        assertFalse(store.settings().enabled)
        controller.pause().stop().destroy()
    }
}
