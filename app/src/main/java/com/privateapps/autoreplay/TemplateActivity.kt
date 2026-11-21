package com.privateapps.autoreplay
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
class TemplateActivity: ComponentActivity() {
    private var feedback by mutableStateOf("")
    private var busy by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {Surface(Modifier.fillMaxSize()) {
                    var number by remember {mutableStateOf("")}
                    var chosen by remember {mutableStateOf<MessageTemplate?>(null)}
                    LazyColumn(Modifier.safeDrawingPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        item {Text("שליחת הודעה מוכנה",style=MaterialTheme.typography.headlineSmall);Text("אשרו את מספר הנמען. מספר השיחה הפעילה אינו מזוהה אוטומטית.")}
                        item {OutlinedTextField(number,{number=it},Modifier.fillMaxWidth(),label={Text("מספר הנמען")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone))}
                        items(Templates(this@TemplateActivity).load()) {t -> OutlinedButton(onClick={chosen=t},modifier=Modifier.fillMaxWidth()) {Text(t.title)}}
                        chosen?.let {t -> item {Text(t.text);Button(onClick={send(number,t.text)},enabled=!busy && Numbers.normalize(number)!=null) {Text("שליחה לפי ההגדרות השמורות")}}}
                        item {Text(feedback);Text("כפוף לסינון ולזמן ההמתנה. במצב אוטומטי תיתכן לחיצה על שליחה לאחר אימות נמען וטקסט.");TextButton(onClick={finish()}) {Text("חזרה")}}
                    }
                }}
            }
        }
    }
    private fun send(raw: String,text: String) {
        if(autoreplay.store.settings().delivery.mode==DeliveryMode.API) {feedback="מסלול API שולח תבנית Meta מאושרת בלבד. שליחת נוסח מקומי זמינה במסלול הטלפון.";return}
        val number=Numbers.normalize(raw) ?: return
        if(busy || autoreplay.dispatcher.active!=null) {feedback="קיימת בקשת שליחה פעילה";return}
        busy=true
        lifecycleScope.launch {
            try {
                val config=autoreplay.store.settings().copy(message=text)
                val reason=withContext(Dispatchers.IO) {
                    when {
                        config.excludeContacts && !Platform.granted(this@TemplateActivity,Manifest.permission.READ_CONTACTS) -> "חסרה הרשאת אנשי קשר"
                        config.excludeContacts && Platform.inContacts(this@TemplateActivity,number) -> "מספר שמור באנשי קשר"
                        else -> null
                    }
                }
                val id=autoreplay.store.accept(null,number,reason,config)
                if(id!=null) {autoreplay.dispatcher.continueFromUser(id,this@TemplateActivity);feedback="נוצרה בקשת שליחה. ראו היסטוריה; אין אישור מסירה."}
                else feedback="דולג לפי ההגדרות. ראו היסטוריה."
            } catch(_: Exception) {feedback="לא ניתן לבדוק הרשאות או ליצור בקשה"}
            finally {busy=false}
        }
    }
}
