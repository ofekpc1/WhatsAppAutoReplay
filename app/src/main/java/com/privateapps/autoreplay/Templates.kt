package com.privateapps.autoreplay
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
data class MessageTemplate(val title: String,val text: String)
class Templates(context: Context) {
    private val prefs=context.getSharedPreferences("templates",Context.MODE_PRIVATE)
    var floating: Boolean
        get()=prefs.getBoolean("floating",false)
        set(value) {prefs.edit().putBoolean("floating",value).apply()}
    fun load(): List<MessageTemplate> = runCatching {
        val raw=prefs.getString("items",null) ?: return listOf(MessageTemplate("פרטי העסק","בית העסק"),MessageTemplate("בקשת פרטים","אפשר לשלוח כאן את הפרטים, ונשמח לעזור."))
        val a=JSONArray(raw)
        List(a.length()) {a.getJSONObject(it).let {j -> MessageTemplate(j.getString("title"),j.getString("text"))}}
    }.getOrElse {emptyList()}
    fun save(items: List<MessageTemplate>) {
        require(items.size<=30 && items.all {it.title.isNotBlank() && it.text.isNotBlank() && it.title.length<=80 && it.text.length<=2000})
        prefs.edit().putString("items",JSONArray().apply {items.forEach {put(JSONObject().put("title",it.title).put("text",it.text))}}.toString()).commit()
    }
}
