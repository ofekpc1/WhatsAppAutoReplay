package com.privateapps.autoreplay

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class CloudResult(val state: String, val detail: String)

class CloudClient(private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) {
    fun payload(c: DeliveryConfig, number: String): String {
        require(DeliveryValidation.api(c))
        require(number.matches(Regex("\\+[1-9][0-9]{6,14}")))
        val template=JSONObject().put("name",c.template).put("language",JSONObject().put("code",c.language))
        if(c.parameters.isNotBlank()) {
            val params=JSONArray()
            c.parameters.lines().forEach { params.put(JSONObject().put("type","text").put("text",it)) }
            template.put("components",JSONArray().put(JSONObject().put("type","body").put("parameters",params)))
        }
        return JSONObject().put("messaging_product","whatsapp").put("to",number.removePrefix("+"))
            .put("type","template").put("template",template).toString()
    }
    fun send(c: DeliveryConfig, number: String, token: String): CloudResult {
        var connection: HttpsURLConnection?=null
        return try {
            val body=payload(c,number).toByteArray(Charsets.UTF_8)
            require(token.isNotBlank() && token.none { it.isWhitespace() || it.code < 32 })
            connection=open(URL("https://graph.facebook.com/${c.version}/${c.phoneId}/messages"))
            connection.apply {
                requestMethod="POST"; instanceFollowRedirects=false; connectTimeout=15_000; readTimeout=20_000
                doOutput=true; setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type","application/json")
                setRequestProperty("Authorization","Bearer $token")
            }
            connection.outputStream.use { it.write(body) }
            val status=connection.responseCode
            if(status in 200..299) {
                val bytes=connection.inputStream.use { stream ->
                    val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(4096)
                    while(true) { val n=stream.read(buffer);if(n<0) break;check(output.size()+n<=65536);output.write(buffer,0,n) }
                    output.toByteArray()
                }
                val id=JSONObject(String(bytes,Charsets.UTF_8)).optJSONArray("messages")?.optJSONObject(0)?.optString("id")
                if(id.isNullOrBlank()) CloudResult("לא ודאי","השרת ענה ללא מזהה הודעה; אין ניסיון חוזר")
                else CloudResult("התקבל ב־Meta","הבקשה התקבלה; אין אישור מסירה ללקוח. מזהה: ${id.take(256)}")
            } else if(status in 400..499) {
                CloudResult("נכשל",if(status==401 || status==403) "ההרשאה נדחתה או פגה; יש לעדכן את מפתח הגישה" else "Meta דחתה את הבקשה (HTTP $status); בדקו תבנית, פרמטרים והרשאות")
            } else CloudResult("לא ודאי","תשובת HTTP $status; לא נשלח שוב אוטומטית")
        } catch(_: Exception) {
            // The peer may have committed the message before the connection was lost.
            CloudResult("לא ודאי","לא התקבל אישור מהשרת; ייתכן שהבקשה נקלטה. אין ניסיון חוזר או מעבר למסלול אחר")
        } finally { connection?.disconnect() }
    }
}

class CloudDelivery(private val context: Context, private val store: Store) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    fun send(e: Entry) {
        if(e.mode!=DeliveryMode.API || e.status!="ממתין") return
        val config=store.settings().delivery
        val token=runCatching { DeviceSecrets(context).get("apiToken") }.getOrNull()
        if(config.mode!=DeliveryMode.API || !DeliveryValidation.api(config) || token.isNullOrBlank()) {
            store.update(e.id,"נכשל","יש להגדיר חיבור Meta ומפתח גישה לפני שליחה");return
        }
        // Persist the attempt BEFORE network I/O. Never retry an ambiguous POST or switch routes.
        if(!store.claim(e.id)) return
        scope.launch {
            val result=withContext(Dispatchers.IO) { CloudClient().send(config,e.number,token) }
            store.update(e.id,result.state,result.detail)
            if(result.state!="התקבל ב־Meta") Notices.attention(context,e.id,result.detail)
        }
    }
}
