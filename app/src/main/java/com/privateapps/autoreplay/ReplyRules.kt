package com.privateapps.autoreplay

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONObject

data class ReplyRules(
    val missed: Boolean = true,
    val incoming: Boolean = false,
    val outgoing: Boolean = false,
    val incomingMessage: String = "תודה שיצרת קשר עם בית העסק! אפשר להמשיך את השיחה כאן.",
    val outgoingMessage: String = "בהמשך לשיחתנו, כאן בית העסק. אפשר לכתוב לנו כאן.",
    val cooldownHours: Int = 24,
    val excluded: String = "",
    val international: Boolean = true,
    val timeoutSeconds: Int = 15,
    val outsideEnabled: Boolean = false,
    val outsideMessage: String = "שלום, הגעת לבית העסק. כרגע אנחנו מחוץ לשעות הפעילות. כתבו לנו ונחזור אליכם בשעות הפעילות.",
    // Sunday first. Empty string means closed; same-day HH:mm-HH:mm.
    val hours: List<String> = listOf("11:00-23:00", "11:00-23:00", "11:00-23:00", "11:00-23:00", "11:00-23:00", "11:00-16:00", "")
) {
    fun excludedNumbers(): List<String> = excluded.split(Regex("[,;\\n]")).map(String::trim).filter(String::isNotEmpty)
    fun valid(): Boolean = cooldownHours in 0..8760 && timeoutSeconds in 5..60 && excludedNumbers().all { Numbers.normalize(it)!=null } && hours.size == 7 && hours.all { it.isBlank() || range(it) != null } &&
        (!incoming || incomingMessage.isNotBlank()) && (!outgoing || outgoingMessage.isNotBlank()) && (!outsideEnabled || outsideMessage.isNotBlank())
    fun allows(call: CallRecord): Boolean = when(call.type) {
        3 -> missed
        1 -> incoming && call.durationSeconds > 0
        2 -> outgoing && call.durationSeconds > 0
        else -> false
    }
    fun isOpen(time: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val local = Instant.ofEpochMilli(time).atZone(zone)
        val interval = range(hours[local.dayOfWeek.value % 7]) ?: return false
        return !local.toLocalTime().isBefore(interval.first) && local.toLocalTime().isBefore(interval.second)
    }
    fun message(call: CallRecord, normal: String): String = when(call.type) {
        1 -> incomingMessage
        2 -> outgoingMessage
        else -> if(outsideEnabled && !isOpen(call.date)) outsideMessage else normal
    }
    fun encode(): String = JSONObject().apply {
        put("missed",missed); put("incoming",incoming); put("outgoing",outgoing)
        put("incomingMessage",incomingMessage); put("outgoingMessage",outgoingMessage)
        put("cooldownHours",cooldownHours); put("outsideEnabled",outsideEnabled); put("outsideMessage",outsideMessage)
        put("excluded",excluded); put("international",international); put("timeoutSeconds",timeoutSeconds)
        put("hours",org.json.JSONArray(hours))
    }.toString()
    companion object {
        fun range(text: String): Pair<LocalTime,LocalTime>? = runCatching {
            require(text.matches(Regex("\\d{2}:\\d{2}-\\d{2}:\\d{2}")))
            val (a,b) = text.split('-').map(LocalTime::parse)
            require(a < b)
            a to b
        }.getOrNull()
        fun decode(raw: String?): ReplyRules = runCatching {
            val j = JSONObject(raw ?: return ReplyRules())
            val d = ReplyRules()
            ReplyRules(j.optBoolean("missed",true),j.optBoolean("incoming"),j.optBoolean("outgoing"),
                j.optString("incomingMessage",d.incomingMessage),j.optString("outgoingMessage",d.outgoingMessage),
                j.optInt("cooldownHours",24),j.optString("excluded",""),j.optBoolean("international",true),j.optInt("timeoutSeconds",15),j.optBoolean("outsideEnabled"),j.optString("outsideMessage",d.outsideMessage),
                j.optJSONArray("hours")?.let { a -> List(a.length()) { a.getString(it) } } ?: d.hours).also { require(it.valid()) }
        }.getOrElse { ReplyRules() }
    }
}
