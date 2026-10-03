package com.privateapps.autoreplay
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
@Composable fun TemplatesEditor(repository: Templates,open: ()->Unit) {
    var entries by remember {mutableStateOf(repository.load())}
    var editing by remember {mutableStateOf<Int?>(null)}
    var title by remember {mutableStateOf("")}
    var text by remember {mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("הודעות מוכנות",style=MaterialTheme.typography.headlineSmall)
        Text("בחרו הודעה בזמן שיחה דרך הכפתור הצף, או פתחו את הרשימה כאן.")
        Button(onClick=open) {Text("בחירת הודעה ושליחה")}
        entries.forEachIndexed {i,t ->
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
                Text(t.title,style=MaterialTheme.typography.titleMedium);Text(t.text)
                Row {
                    TextButton(onClick={editing=i;title=t.title;text=t.text}) {Text("עריכה")}
                    TextButton(onClick={entries=entries.filterIndexed {index,_ -> index!=i};repository.save(entries);editing=null;title="";text=""}) {Text("מחיקה")}
                }
            }}
        }
        HorizontalDivider();Text(if(editing==null) "הוספת הודעה" else "עריכת הודעה")
        OutlinedTextField(title,{title=it.take(80)},Modifier.fillMaxWidth(),label={Text("שם ההודעה")})
        OutlinedTextField(text,{text=it.take(2000)},Modifier.fillMaxWidth(),label={Text("תוכן ההודעה")},minLines=3)
        Button(onClick={
            entries=entries.toMutableList().also {list -> val t=MessageTemplate(title.trim(),text);val index=editing;if(index==null) list.add(t) else list[index]=t}
            repository.save(entries);editing=null;title="";text=""
        },enabled=title.isNotBlank() && text.isNotBlank() && (editing!=null || entries.size<30)) {Text("שמירת הודעה")}
        if(editing!=null) TextButton(onClick={editing=null;title="";text=""}) {Text("ביטול עריכה")}
    }
}
