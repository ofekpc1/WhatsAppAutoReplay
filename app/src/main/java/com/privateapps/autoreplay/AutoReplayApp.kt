package com.privateapps.autoreplay

import android.app.Application
import android.content.Context

class AutoReplayApp : Application() {
    lateinit var store: Store
    lateinit var dispatcher: ReplyDispatcher
    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        store.recover()
        Notices.create(this)
        dispatcher = ReplyDispatcher(this, store)
    }
}
val Context.autoreplay: AutoReplayApp get() = applicationContext as AutoReplayApp
