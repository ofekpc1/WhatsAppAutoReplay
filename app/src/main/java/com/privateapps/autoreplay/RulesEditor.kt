package com.privateapps.autoreplay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable fun RulesEditor(rules: ReplyRules, enabled: Boolean, change: (ReplyRules)->Unit) {
    @Composable fun option(label: String, checked: Boolean, action: (Boolean)->Unit) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(label,Modifier.weight(1f).padding(top=12.dp)); Switch(checked,action,enabled=enabled)
        }
    }
    @Composable fun message(label: String, value: String, action: (String)->Unit) {
        OutlinedTextField(value,{action(it.take(2000))},Modifier.fillMaxWidth(),enabled=enabled,label={Text(label)},minLines=3)
    }
    Card(Modifier.fillMaxWidth().padding(vertical=12.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("סוגי שיחות והודעות",style=MaterialTheme.typography.titleLarge)
            option("שיחה שלא נענתה",rules.missed) {change(rules.copy(missed=it))}
            option("אחרי שיחה נכנסת שנענתה",rules.incoming) {change(rules.copy(incoming=it))}
            if(rules.incoming) message("הודעה בסיום שיחה נכנסת",rules.incomingMessage) {change(rules.copy(incomingMessage=it))}
            option("אחרי שיחה יוצאת",rules.outgoing) {change(rules.copy(outgoing=it))}
            if(rules.outgoing) message("הודעה בסיום שיחה יוצאת",rules.outgoingMessage) {change(rules.copy(outgoingMessage=it))}
            Text("בשיחה שנענתה או יוצאת נדרש משך שיחה חיובי ביומן. הגדרות WhatsApp, נגישות ואנשי קשר משותפות לכל הסוגים.",style=MaterialTheme.typography.bodySmall)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("שעות פעילות",style=MaterialTheme.typography.titleLarge)
            option("הודעה שונה מחוץ לשעות — לשיחה שלא נענתה",rules.outsideEnabled) {change(rules.copy(outsideEnabled=it))}
            if(rules.outsideEnabled) {
                message("הודעה מחוץ לשעות הפעילות",rules.outsideMessage) {change(rules.copy(outsideMessage=it))}
                Text("לפי שעת תחילת השיחה ואזור הזמן בטלפון. הזינו לדוגמה 11:00-23:00; שדה ריק = סגור. שעות באותו יום בלבד.")
                listOf("ראשון","שני","שלישי","רביעי","חמישי","שישי","שבת").forEachIndexed { i, day ->
                    OutlinedTextField(rules.hours[i],{value -> change(rules.copy(hours=rules.hours.toMutableList().also {it[i]=value}))},
                        Modifier.fillMaxWidth(),enabled=enabled,label={Text(day)},singleLine=true,
                        isError=rules.hours[i].isNotBlank() && ReplyRules.range(rules.hours[i])==null)
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth().padding(vertical=12.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("סינון מספרים וטעינת WhatsApp",style=MaterialTheme.typography.titleLarge)
            option("אפשר הודעות למספרים מחוץ לישראל",rules.international) {change(rules.copy(international=it))}
            OutlinedTextField(rules.excluded,{change(rules.copy(excluded=it))},Modifier.fillMaxWidth(),enabled=enabled,
                label={Text("מספרים מוחרגים — מספר בכל שורה")},minLines=2,
                isError=rules.excludedNumbers().any {Numbers.normalize(it)==null})
            var timeout by remember { mutableStateOf(rules.timeoutSeconds.toString()) }
            OutlinedTextField(timeout,{timeout=it;change(rules.copy(timeoutSeconds=it.toIntOrNull() ?: -1))},
                Modifier.fillMaxWidth(),enabled=enabled,label={Text("זמן מרבי לאימות שיחה וטקסט — 5 עד 60 שניות")},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,isError=rules.timeoutSeconds !in 5..60)
            Text("כשאימות המספר והטקסט מצליח ניתן לשלוח מיד; בתום הזמן הפעולה נעצרת. אין לחיצה על סמך טיימר בלבד.",style=MaterialTheme.typography.bodySmall)
        }
    }
    Card(Modifier.fillMaxWidth().padding(vertical=12.dp)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("המתנה בין הודעות לאותו מספר",style=MaterialTheme.typography.titleLarge)
            var hours by remember { mutableStateOf(rules.cooldownHours.toString()) }
            OutlinedTextField(hours,{value -> hours=value; change(rules.copy(cooldownHours=value.toIntOrNull() ?: -1))},
                Modifier.fillMaxWidth(),enabled=enabled,label={Text("שעות: 0 = ללא המתנה")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),isError=rules.cooldownHours !in 0..8760)
            Text("24 שעות = יום; 720 שעות = 30 ימים. ללא המתנה תיתכן הודעה בכל שיחה חדשה. אין ניסיון חוזר אוטומטי כשמצב השליחה אינו ודאי.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
