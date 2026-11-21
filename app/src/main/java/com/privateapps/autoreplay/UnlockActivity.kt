package com.privateapps.autoreplay

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.view.WindowManager

interface UnlockDriver {
    val busy: Boolean
    val requestId: Long?
    fun begin(entry: Entry, testOnly: Boolean = false)
    fun finish(success: Boolean)
    fun test()
}
object UnlockConnection { var driver: UnlockDriver? = null }

/** Only requests the system PIN screen; never draws an imitation lock screen. */
class UnlockActivity : Activity() {
    private var requestId: Long?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if(android.os.Build.VERSION.SDK_INT<28 || UnlockConnection.driver?.busy!=true) { finish();return }
        requestId=UnlockConnection.driver?.requestId
        current=java.lang.ref.WeakReference(this)
        setShowWhenLocked(true);setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this,object: KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { finish() }
            override fun onDismissCancelled() { abort() }
            override fun onDismissError() { abort() }
        })
    }
    private fun abort() {
        if(requestId!=null && UnlockConnection.driver?.requestId==requestId) UnlockConnection.driver?.finish(false)
        finish()
    }
    override fun onDestroy() { if(current?.get()===this) current=null;super.onDestroy() }
    companion object {
        private var current: java.lang.ref.WeakReference<UnlockActivity>?=null
        fun close(id: Long) { current?.get()?.takeIf { it.requestId==id }?.finish() }
    }
}
