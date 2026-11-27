package com.privateapps.autoreplay

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var updates: AppUpdates
    private var hasUnsavedChanges = false
    private var deliveryDirty by mutableStateOf(false)
    private fun mayInstallUpdate() = !hasUnsavedChanges && !deliveryDirty && !autoreplay.store.settings().enabled && !autoreplay.dispatcher.busy
    private var feedback by mutableStateOf("")
    private var revision by mutableIntStateOf(0)
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++;feedback="ההרשאות עודכנו. אם הרשאה נדחתה, בדקו את הגדרות האפליקציה." }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updates = AppUpdates(this, lifecycleScope, ::mayInstallUpdate)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme=lightColorScheme(primary=androidx.compose.ui.graphics.Color(0xFF087D70))) {
                    Surface(Modifier.fillMaxSize()) { Home() }
                }
            }
        }
    }
    override fun onResume() { super.onResume();revision++;updates.resumed() }
    override fun onPause() { updates.paused();super.onPause() }
    override fun onDestroy() { updates.close();super.onDestroy() }
    private fun settings(action: String, data: Uri?=null) {
        runCatching { startActivity(Intent(action,data)) }.onFailure { feedback="מסך ההגדרות אינו זמין במכשיר זה" }
    }
    private fun toggle(on: Boolean) {
        if(!on) {
            autoreplay.store.disable("כובה על ידי המשתמש");autoreplay.dispatcher.cancel()
            stopService(Intent(this,CallMonitorService::class.java));revision++;return
        }
        val s=autoreplay.store.settings()
        if(!Platform.granted(this,Manifest.permission.READ_CALL_LOG) || !Platform.accountPermission(this) || !Platform.notifications(this) ||
            (s.excludeContacts && !Platform.granted(this,Manifest.permission.READ_CONTACTS))) {
            feedback="לפני ההפעלה יש לאשר יומן שיחות, אימות חשבון SIM והתראות, ואנשי קשר אם הסינון מופעל.";return
        }
        if(s.message.isBlank()) { feedback="יש לשמור הודעה שאינה ריקה";return }
        lifecycleScope.launch {
            try {
                val baseline=withContext(Dispatchers.IO) { CallMonitorService.baseline(this@MainActivity) }
                autoreplay.store.enable(baseline)
                ContextCompat.startForegroundService(this@MainActivity,Intent(this@MainActivity,CallMonitorService::class.java))
                feedback="ניטור הופעל. רק שיחות שמתחילות מעכשיו יעובדו."
            } catch(_: Exception) { autoreplay.store.disable("ההפעלה נכשלה");feedback="המערכת חסמה את הניטור או את יומן השיחות. בדקו הרשאות והתקנה." }
            revision++
        }
    }
    private fun test(raw: String) {
        val s=autoreplay.store.settings()
        if(s.message.isBlank()) {feedback="יש לשמור הודעה קודם";return}
        val number=Numbers.normalize(raw)
        if(number==null) { autoreplay.store.log(raw,"דולג","מספר בדיקה לא תקין");feedback="הזינו מספר טלפון תקין";revision++;return }
        lifecycleScope.launch {
            val reason=withContext(Dispatchers.IO) {
                when {
                    s.excludeContacts && !Platform.granted(this@MainActivity,Manifest.permission.READ_CONTACTS) -> "חסרה הרשאת אנשי קשר"
                    s.excludeContacts -> runCatching { if(Platform.inContacts(this@MainActivity,number)) "מספר שמור באנשי קשר" else null }.getOrElse { "בדיקת אנשי קשר נכשלה" }
                    else -> null
                }
            }
            val id=autoreplay.store.accept(null,number,reason,s)
            if(id!=null) autoreplay.dispatcher.continueFromUser(id,this@MainActivity)
            feedback=if(id==null) "הבדיקה דולגה — ראו היסטוריה" else "נוצרה בקשת בדיקה אמיתית; ראו סטטוס בהיסטוריה"
            revision++
        }
    }
    @Composable private fun Home() {
        LaunchedEffect(Unit) { while(isActive) { delay(1000);revision++ } }
        val snapshot=revision
        val current=remember(snapshot) { autoreplay.store.settings() }
        val history=remember(snapshot) { autoreplay.store.history() }
        var message by remember { mutableStateOf(current.message) }
        var business by remember { mutableStateOf(current.pkg==BUSINESS) }
        var auto by remember { mutableStateOf(current.auto) }
        var contacts by remember { mutableStateOf(current.excludeContacts) }
        var rules by remember { mutableStateOf(current.rules) }
        var number by remember { mutableStateOf("") }
        val dirty=rules!=current.rules || message!=current.message || business!=(current.pkg==BUSINESS) || auto!=current.auto || contacts!=current.excludeContacts
        SideEffect { hasUnsavedChanges = dirty }
        var tab by remember { mutableIntStateOf(0) }
        val tabs=listOf("הודעות","תבניות","היסטוריה","הגדרות")
        Scaffold(bottomBar={
            NavigationBar { tabs.forEachIndexed {i,label -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("✉","☰","◷","⚙")[i])},label={Text(label)}) } }
        }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=20.dp)) {
            item {
                Text("Whatsapp Auto replay",style=MaterialTheme.typography.headlineMedium)
                if(!BuildConfig.AUTOMATION_AVAILABLE) Text("עותק אבחון נפרד ללא שירות נגישות: תמיכה ב־API ובשליחה ידנית. אין פתיחת נעילה או לחיצה אוטומטית ב־WhatsApp.",color=MaterialTheme.colorScheme.error)
                Text("ניהול הודעות אחרי שיחות",style=MaterialTheme.typography.titleMedium)
                Text(if(current.delivery.mode==DeliveryMode.API) "מסלול Meta API · פרטי השליחה מועברים ל־Meta" else "מסלול טלפון · נתונים נשמרים במכשיר",style=MaterialTheme.typography.bodySmall)
            }
            item {
                Card { Column(Modifier.padding(16.dp)) {
                    Toggle("מענה אוטומטי לשיחות",current.enabled) { if(!it || !dirty) toggle(it) else feedback="יש לשמור את שינויי ההגדרות לפני הפעלת המענה" }
                    Text(if(current.enabled) "פעיל — שיחות מטופלות לפי ההגדרות השמורות" else "מושהה — אין תגובות אוטומטיות לשיחות",style=MaterialTheme.typography.bodyMedium)
                    Text("המתג מפעיל או משהה טיפול בשיחות. ההגדרות זמינות לעריכה גם כשהמענה פעיל. גם הכפתור הצף דורש שהמתג יהיה פעיל.",style=MaterialTheme.typography.bodySmall)
                    Text("לאחר הפעלה מחדש או עצירת האפליקציה יש להפעיל שוב. אין טיפול בשיחות WhatsApp או בשיחה ממתינה.",style=MaterialTheme.typography.bodySmall)
                } }
            }
            if(tab==0 || tab==3) item { UpdateCard(updates, !dirty && !deliveryDirty && !current.enabled && !autoreplay.dispatcher.busy) }
            if(feedback.isNotBlank()) item { Text(feedback,color=MaterialTheme.colorScheme.primary) }
            if(tab==0) item {
                if(current.delivery.mode==DeliveryMode.API) Text("מסלול API פעיל: נשלחת התבנית המאושרת מהגדרות המסלול. הטקסט החופשי והמתג של נגישות להלן שייכים למסלול הטלפון.",color=MaterialTheme.colorScheme.primary)
                OutlinedTextField(message,{ message=it.take(2000) },Modifier.fillMaxWidth(),enabled=true,label={Text("הודעת התשובה")},minLines=5)
                Toggle("WhatsApp Business (כבוי = WhatsApp רגיל)",business) {business=it}
                if(BuildConfig.AUTOMATION_AVAILABLE) Toggle("שליחה אוטומטית באמצעות נגישות",auto) {auto=it}
                Text(if(auto) "האפליקציה עשויה לפתוח WhatsApp וללחוץ שליחה רק לאחר אימות מספר וטקסט. אם אי אפשר לאמת, תידרש פעולה ידנית." else "הודעה מוכנה תיפתח אחרי לחיצה שלכם. שליחה בתוך WhatsApp היא ידנית.",style=MaterialTheme.typography.bodySmall)
                Toggle("החרגת מספרים מאנשי הקשר",contacts) {
                    contacts=it
                    if(it && !Platform.granted(this@MainActivity,Manifest.permission.READ_CONTACTS)) feedback="סינון אנשי קשר דורש גישה לאנשי קשר לצורך השוואת המספר בלבד. אשרו בכפתור ההרשאה למטה."
                }
                Text("זמן ההמתנה חל על כל סוגי השיחות והבדיקות. כל שיחה מעובדת פעם אחת בלבד, גם ללא המתנה.",style=MaterialTheme.typography.bodySmall)
                RulesEditor(rules,true) { rules=it }
                Button(onClick={autoreplay.dispatcher.cancel();autoreplay.store.save(message,if(business) BUSINESS else WA,auto,contacts,rules);revision++;feedback="ההגדרות נשמרו. בקשות שטרם נשלחו בוטלו; השיחות הבאות יטופלו לפי השינויים."},enabled=dirty && message.isNotBlank() && rules.valid()) {Text("שמירת הגדרות")}
                Text(if(dirty) "יש שינויים שלא נשמרו. עד השמירה המענה פועל לפי ההגדרות הקודמות." else "כל ההגדרות נשמרו. אפשר לערוך גם כשהמענה פעיל.",style=MaterialTheme.typography.bodySmall)
            }
            if(tab==1) item {
                val templates=remember { Templates(this@MainActivity) }
                var floating by remember {mutableStateOf(templates.floating)}
                Toggle("כפתור צף בזמן שיחה",floating) {floating=it;templates.floating=it}
                Text("הכפתור מופיע כשהניטור פעיל, בזמן שיחה ובמכשיר פתוח. הוא פותח רשימת הודעות עם הזנת מספר נמען.")
                if(floating) {
                    Text("מצב שיחה משמש להצגת הכפתור בלבד; הצגה מעל אפליקציות נדרשת כדי להציגו מעל מסך השיחה.")
                    PermissionLine("מצב שיחה",Platform.granted(this@MainActivity,Manifest.permission.READ_PHONE_STATE)) {permissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))}
                    PermissionLine("הצגה מעל אפליקציות",AndroidSettings.canDrawOverlays(this@MainActivity)) {settings(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))}
                }
                TemplatesEditor(templates) {startActivity(Intent(this@MainActivity,TemplateActivity::class.java))}
            }
            if(tab==3) item {
                DeliveryEditor(onDirty = { deliveryDirty = it }) { revision++ }
                HorizontalDivider()
                Text("הרשאות ומצב",style=MaterialTheme.typography.titleLarge)
                Text("יומן השיחות נחוץ לזיהוי סוג השיחה הסלולרית וסיומה. במסלול API נדרשת גישה לאינטרנט. אין קריאת SMS או חיוג.")
                PermissionLine("יומן שיחות",Platform.granted(this@MainActivity,Manifest.permission.READ_CALL_LOG)) {permissions.launch(arrayOf(Manifest.permission.READ_CALL_LOG))}
                if(Build.VERSION.SDK_INT>=31) {
                    Text("אימות חשבון SIM דורש הרשאת מספרי טלפון ב־Android 12 ומעלה, כדי להבחין בין שיחה סלולרית לשיחת VoIP. מספר ה־SIM עצמו אינו נשמר.",style=MaterialTheme.typography.bodySmall)
                    PermissionLine("אימות חשבון SIM",Platform.accountPermission(this@MainActivity)) {permissions.launch(arrayOf(Manifest.permission.READ_PHONE_NUMBERS))}
                }
                Text("Android עשוי לחסום הרשאה זו לפי מתקין ה־APK או מדיניות המכשיר. האפליקציה אינה משנה את חייגן ברירת המחדל.",style=MaterialTheme.typography.bodySmall)
                PermissionLine("התראות",Platform.notifications(this@MainActivity)) {
                    if(Build.VERSION.SDK_INT>=33 && !Platform.granted(this@MainActivity,Manifest.permission.POST_NOTIFICATIONS)) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    else runCatching { startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,packageName)) }.onFailure { feedback="פתחו פרטי אפליקציה כדי לאפשר התראות" }
                }
                if(contacts) PermissionLine("אנשי קשר — לסינון בלבד",Platform.granted(this@MainActivity,Manifest.permission.READ_CONTACTS)) { permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }
                if(BuildConfig.AUTOMATION_AVAILABLE) {
                    PermissionLine("שירות נגישות מחובר",AccessibilityConnection.connected!=null) { settings(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS) }
                    Text("נגישות מאפשרת לאמת מספר וטקסט ב־WhatsApp וללחוץ שליחה. במסלול הניסיוני בלבד היא מזינה קוד במקלדת המערכת ונועלת מחדש. אינה נדרשת למסלול API. ב־Android 13 ומעלה ייתכן צורך ב׳אפשר הגדרות מוגבלות׳.",style=MaterialTheme.typography.bodySmall)
                }
                Text("WhatsApp שנבחר: "+if(Platform.installed(this@MainActivity,if(business) BUSINESS else WA)) "מותקן" else "אינו מותקן")
                OutlinedButton(onClick={settings(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName"))}) {Text("פרטי אפליקציה והרשאות")}
                OutlinedButton(onClick={settings(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)}) {Text("הגדרות חיסכון בסוללה")}
                Text("חיסכון בסוללה עלול לעצור ניטור בכל המסלולים. מסלול הטלפון הרגיל ממתין לפתיחה ידנית; API אינו דורש פתיחה; המסלול הניסיוני תלוי בתמיכת מסך הנעילה במכשיר.",style=MaterialTheme.typography.bodySmall)
            }
            if(tab==3) item {
                HorizontalDivider()
                Text("בדיקת שליחה",style=MaterialTheme.typography.titleLarge)
                OutlinedTextField(number,{number=it},Modifier.fillMaxWidth(),label={Text("מספר טלפון")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),singleLine=true)
                Text("זו בקשת שליחה אמיתית, לפי ההגדרות השמורות. ברירת המחדל למספר מקומי היא ישראל.",style=MaterialTheme.typography.bodySmall)
                Button(onClick={test(number)},enabled=!dirty && number.isNotBlank() && autoreplay.dispatcher.active==null) {Text("בדיקה עם המספר")}
            }
            if(tab==2) {
            item { HorizontalDivider();Text("היסטוריית פעולות",style=MaterialTheme.typography.titleLarge);Text("מוצגות עד 200 הפעולות האחרונות. ׳הופעל ניסיון שליחה׳ אינו אישור מסירה.",style=MaterialTheme.typography.bodySmall) }
            if(history.isEmpty()) item {Text("אין פעולות עדיין")}
            items(history,key={it.id}) { entry ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(entry.number,style=MaterialTheme.typography.titleMedium)
                    Text(SimpleDateFormat("dd/MM/yyyy HH:mm:ss",Locale.forLanguageTag("he")).format(Date(entry.time)))
                    Text(entry.status,color=MaterialTheme.colorScheme.primary)
                    Text(entry.detail,style=MaterialTheme.typography.bodySmall)
                    Text("מסלול: ${entry.mode.name} · בקשה: ${entry.requestId}",style=MaterialTheme.typography.bodySmall)
                    if(entry.status=="ממתין" && autoreplay.dispatcher.active?.entry?.id!=entry.id) Button(onClick={autoreplay.dispatcher.continueFromUser(entry.id,this@MainActivity);revision++},enabled=autoreplay.dispatcher.active==null) {Text("המשך לאחר פתיחת המכשיר")}
                    if(entry.manual) OutlinedButton(onClick={autoreplay.dispatcher.continueFromUser(entry.id,this@MainActivity,true);revision++},enabled=autoreplay.dispatcher.active==null) {Text("פתיחת הודעה לשליחה ידנית")}
                } }
            }
            }
        }
        }
    }
    @Composable private fun Toggle(label: String, value: Boolean, enabled: Boolean=true, onChange: (Boolean)->Unit) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(label,Modifier.weight(1f).padding(top=12.dp,end=8.dp));Switch(value,onChange,enabled=enabled)
        }
    }
    @Composable private fun PermissionLine(label: String, granted: Boolean, action: ()->Unit) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("$label: ${if(granted) "פעיל" else "חסר / כבוי"}",Modifier.weight(1f).padding(top=12.dp))
            TextButton(onClick=action) {Text("הגדרות / אישור")}
        }
    }
}
