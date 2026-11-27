package com.privateapps.autoreplay

import android.app.Activity
import android.os.Looper
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class AppUpdatesTest {
    @Test fun checkOnlyShowsOfferAndCannotStartInstallerWhileReplyActive() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val requests = AtomicInteger()
        val json = """{"packageName":"${BuildConfig.APPLICATION_ID}","versionCode":7,"versionName":"0.4.2","url":"https://ofekpc.co.il/downloads/whatsapp-autoreplay/Whatsapp-Auto-replay-0.4.2.apk.bin","sha256":"${"a".repeat(64)}","size":10,"notes":"test"}""".toByteArray()
        val client = AppUpdateClient(activity) {
            requests.incrementAndGet()
            object : AppUpdateClient.Response {
                override fun stream() = ByteArrayInputStream(json)
                override fun length() = json.size.toLong()
                override fun close() = Unit
            }
        }
        val updates = AppUpdates(activity, scope, { false }, client)
        try {
            updates.resumed()
            repeat(200) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (updates.busy) Thread.sleep(5)
            }
            assertTrue(updates.newer)
            assertEquals(1, requests.get())
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
            updates.install()
            assertTrue(updates.status.contains("להשהות"))
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
            updates.paused(); updates.resumed()
            assertEquals(1, requests.get())
        } finally { updates.close(); scope.cancel(); activity.finish() }
    }

    @Test fun offlineFailureDoesNotEnableMonitoringOrChangeReplySettings() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val before = activity.autoreplay.store.settings()
        val updates = AppUpdates(activity, scope, { true }, AppUpdateClient(activity) { throw IOException("offline") })
        try {
            updates.resumed()
            repeat(200) { Shadows.shadowOf(Looper.getMainLooper()).idle(); if (updates.busy) Thread.sleep(5) }
            assertFalse(updates.busy)
            assertTrue(updates.status.contains("לא הצליחה"))
            assertEquals(before, activity.autoreplay.store.settings())
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        } finally { updates.close(); scope.cancel(); activity.finish() }
    }
}
