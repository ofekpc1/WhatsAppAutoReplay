package com.privateapps.autoreplay
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
class FloatingReplies(private val context: Context) {
    private var button: Button?=null
    private val windows=context.getSystemService(WindowManager::class.java)
    @Suppress("DEPRECATION")
    fun refresh() {
        val allowed=Templates(context).floating && Settings.canDrawOverlays(context) && Platform.granted(context,Manifest.permission.READ_PHONE_STATE) && Platform.unlocked(context)
        val inCall=allowed && runCatching {context.getSystemService(TelephonyManager::class.java).callState==TelephonyManager.CALL_STATE_OFFHOOK}.getOrDefault(false)
        if(!inCall) {close();return}
        if(button!=null) return
        val view=Button(context).apply {
            text="הודעות\nWhatsApp";contentDescription="בחירת הודעה מוכנה בזמן שיחה"
            setOnClickListener {if(Platform.unlocked(context)) runCatching {context.startActivity(Intent(context,TemplateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}
        }
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply {gravity=Gravity.END or Gravity.CENTER_VERTICAL;x=12}
        runCatching {windows.addView(view,params);button=view}
    }
    fun close() {button?.let {runCatching {windows.removeView(it)}};button=null}
}
