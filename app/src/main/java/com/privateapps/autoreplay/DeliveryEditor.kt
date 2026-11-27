package com.privateapps.autoreplay

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable fun DeliveryEditor(onDirty: (Boolean) -> Unit = {}, onSaved: () -> Unit) {
    val context=LocalContext.current
    val store=context.autoreplay.store
    val secrets=remember { DeviceSecrets(context) }
    var config by remember { mutableStateOf(store.settings().delivery) }
    var pin by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    SideEffect { onDirty(config != store.settings().delivery || pin.isNotEmpty() || token.isNotEmpty() || consent) }
    DisposableEffect(Unit) { onDispose { onDirty(false) } }
    Text("מסלול השליחה",style=MaterialTheme.typography.titleLarge)
    listOf(DeliveryMode.PHONE to "דרך הטלפון — המצב הקיים", DeliveryMode.UNLOCK to "פתיחה ושליחה — ניסיוני", DeliveryMode.API to "Meta API — גם בטלפון נעול").forEach { (mode,label) ->
        Row(Modifier.fillMaxWidth()) {
            RadioButton(selected=config.mode==mode,onClick={config=config.copy(mode=mode);consent=false},enabled=mode!=DeliveryMode.UNLOCK || BuildConfig.AUTOMATION_AVAILABLE && Build.VERSION.SDK_INT>=28)
            Text(label,Modifier.padding(top=12.dp))
        }
    }
    if(config.mode==DeliveryMode.UNLOCK) {
        Text("ניסיון אחד להזנת קוד במקלדת המערכת המזוהה, שליחה ונעילה מחדש. עלול לא לעבוד במכשיר שלך. כישלון חוסם ניסיונות נוספים עד חידוש ידני. אין תמיכה בסיסמה או בתבנית נעילה.")
        Text("קוד הנעילה נשמר מוצפן במכשיר בלבד. כאשר מצב זה פעיל, האפליקציה יכולה לפתוח את הטלפון. שירות הנגישות מקבל גם גישה למסך הנעילה של המערכת.")
        OutlinedTextField(pin,{pin=it.filter { ch -> ch in '0'..'9' }.take(12)},Modifier.fillMaxWidth(),label={Text(if(secrets.has("pin")) "קוד חדש (ריק = שמירת הקיים)" else "קוד הנעילה")},
            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),singleLine=true)
        Row { Checkbox(consent,{consent=it});Text("אני מבין/ה ומפעיל/ה פתיחה ניסיונית במכשיר שלי",Modifier.padding(top=12.dp)) }
        Text("נדרשת הפעלת שליחה אוטומטית באמצעות נגישות במסך הודעות. מומלץ לבצע קודם בדיקת פתיחה ללא שליחת הודעה.")
        OutlinedButton(onClick={
            if(store.settings().delivery.mode!=DeliveryMode.UNLOCK || UnlockConnection.driver==null) feedback="שמרו מסלול ניסיוני וחברו שירות נגישות קודם"
            else { UnlockConnection.driver?.test();feedback="נעלו כעת את הטלפון. הבדיקה תתחיל בעוד 10 שניות, ללא שליחת הודעה." }
        }) { Text("בדיקת פתיחה בלבד בעוד 10 שניות") }
    }
    if(config.mode==DeliveryMode.API) {
        Text("חיבור ישיר ל־Meta, ללא שרת נוסף. נדרשים חשבון WhatsApp Business Platform, תבנית מאושרת והרשאת שליחה. עשוי להיות חיוב לכל הודעה. המספר והתבנית יישלחו ל־Meta ב־HTTPS.")
        OutlinedTextField(config.phoneId,{config=config.copy(phoneId=it.trim())},Modifier.fillMaxWidth(),label={Text("Phone Number ID — מזהה ב־Meta")},singleLine=true)
        OutlinedTextField(config.template,{config=config.copy(template=it.trim())},Modifier.fillMaxWidth(),label={Text("שם תבנית מאושרת")},singleLine=true)
        OutlinedTextField(config.language,{config=config.copy(language=it.trim())},Modifier.fillMaxWidth(),label={Text("קוד שפה, למשל he או en_US")},singleLine=true)
        OutlinedTextField(config.version,{config=config.copy(version=it.trim())},Modifier.fillMaxWidth(),label={Text("גרסת Graph API, למשל v27.0")},singleLine=true)
        OutlinedTextField(config.parameters,{config=config.copy(parameters=it.take(2000))},Modifier.fillMaxWidth(),label={Text("פרמטרי גוף התבנית לפי הסדר, אחד בשורה")})
        Text("נתמכת תבנית טקסט עם פרמטרי גוף ממוספרים. ללא פרמטרים — השאירו ריק. הטקסט החופשי והנוסחים לפי סוג שיחה במסך הודעות אינם נשלחים במסלול זה.")
        OutlinedTextField(token,{token=it.trim()},Modifier.fillMaxWidth(),label={Text(if(secrets.has("apiToken")) "מפתח גישה חדש (ריק = שמירת הקיים)" else "Access Token עם whatsapp_business_messaging")},
            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true)
        Text("המפתח מוצפן ב־Android Keystore ואינו נכלל ב־APK. תוקפו והרשאותיו מנוהלים ב־Meta. לא ניתן לאשר מסירה ללא חיבור Webhook; אחרי ניתוק אין שליחה חוזרת אוטומטית.")
    }
    Button(onClick={
        try {
            check(Platform.unlocked(context)) { "פתחו את המכשיר לפני שינוי הגדרות" }
            require(config.mode!=DeliveryMode.API || DeliveryValidation.api(config)) { "בדקו את מזהה המספר, התבנית, השפה וגרסת API" }
            require(config.mode!=DeliveryMode.API || token.isNotBlank() || secrets.has("apiToken")) { "חסר מפתח גישה" }
            require(config.mode!=DeliveryMode.UNLOCK || consent && store.settings().auto && (DeliveryValidation.pin(pin) || pin.isEmpty() && secrets.has("pin"))) { "נדרשים קוד, הסכמה ושליחה אוטומטית באמצעות נגישות" }
            context.autoreplay.dispatcher.cancel()
            if(config.mode==DeliveryMode.UNLOCK && pin.isNotEmpty()) secrets.put("pin",pin)
            if(config.mode==DeliveryMode.API && token.isNotEmpty()) { require(token.length<=8192 && token.none { it.isWhitespace() || it.code<32 });secrets.put("apiToken",token) }
            store.saveDelivery(config)
            if(config.mode==DeliveryMode.UNLOCK) DeliverySettings(context).arm()
            pin="";token="";consent=false;feedback="המסלול נשמר. בקשות ממתינות בוטלו. ניסיון רשת שכבר התחיל אינו ניתן לביטול.";onSaved()
        } catch(e: IllegalArgumentException) { feedback=e.message ?: "הגדרות אינן תקינות" }
          catch(_: Exception) { feedback="לא ניתן לשמור באופן מאובטח במכשיר. לא הופעל מסלול חדש." }
    }) { Text("שמירת המסלול / חידוש לאחר כישלון") }
    OutlinedButton(onClick={
        runCatching {
            check(Platform.unlocked(context));context.autoreplay.dispatcher.cancel()
            store.saveDelivery(DeliveryConfig());secrets.clear();config=DeliveryConfig();pin="";token="";consent=false
            feedback="הסודות נמחקו ונבחר המסלול הרגיל";onSaved()
        }.onFailure { feedback="מחיקה נכשלה; בדקו שהמכשיר פתוח" }
    }) { Text("מחיקת קוד ומפתח וחזרה למסלול הרגיל") }
    if(feedback.isNotEmpty()) Text(feedback)
}
