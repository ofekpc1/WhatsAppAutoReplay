package com.privateapps.autoreplay

import com.google.i18n.phonenumbers.PhoneNumberUtil

const val DAY = 24 * 60 * 60 * 1000L
const val WA = "com.whatsapp"
const val BUSINESS = "com.whatsapp.w4b"
const val DEFAULT_MESSAGE = "שלום, הגעת לבית העסק 👋\nכרגע לא יכולנו לענות לשיחה שלך.\nאפשר לכתוב לנו כאן במה נוכל לעזור, ונחזור אליך בהקדם."
object Numbers {
    private val util = PhoneNumberUtil.getInstance()
    fun normalize(raw: String?): String? = runCatching {
        val clean = raw?.trim()?.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "") ?: return null
        if (!clean.matches(Regex("[+0-9() .-]+"))) return null
        val parsed = util.parse(if (clean.startsWith("00")) "+" + clean.drop(2) else clean, "IL")
        if (!util.isValidNumber(parsed) || parsed.hasExtension()) return null
        util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }.getOrNull()
}
data class CallRecord(val id: Long, val date: Long, val type: Int, val number: String?, val presentation: Int, val durationSeconds: Long = 0)
data class Eligibility(val number: String? = null, val reason: String? = null)
object Policy {
    fun eligible(call: CallRecord, enabledAt: Long, baseline: Long, seen: Boolean,
                 hasPermission: Boolean, contactsExcluded: Boolean, contactsGranted: Boolean,
                 inContacts: Boolean, lastReserved: Long?, now: Long, rules: ReplyRules = ReplyRules()): Eligibility {
        val displayNumber = if(call.presentation==1) Numbers.normalize(call.number) else null
        if (!hasPermission) return Eligibility(displayNumber, "חסרה הרשאת יומן שיחות")
        if (seen) return Eligibility(displayNumber, "השיחה כבר עובדה")
        if (call.id <= baseline || call.date <= enabledAt) return Eligibility(displayNumber, "שיחה ישנה")
        val ended = call.date + call.durationSeconds.coerceIn(0, 7 * 24 * 3600) * 1000
        if (call.date > now || ended > now || now - ended > 10 * 60_000) return Eligibility(displayNumber, "שיחה מחוץ לחלון העיבוד")
        if (!rules.allows(call)) return Eligibility(displayNumber, "סוג השיחה כבוי או שלא נרשם משך שיחה")
        if (call.presentation != 1) return Eligibility(reason = "מספר חסוי")
        val number = Numbers.normalize(call.number) ?: return Eligibility(reason = "מספר לא תקין")
        if (rules.excludedNumbers().any { Numbers.normalize(it)==number }) return Eligibility(number,"מספר ברשימת ההחרגות")
        if (!rules.international && !number.startsWith("+972")) return Eligibility(number,"מספר מחוץ לישראל")
        if (contactsExcluded && !contactsGranted) return Eligibility(number, "חסרה הרשאת אנשי קשר; לא נשלח")
        if (contactsExcluded && inContacts) return Eligibility(number, "מספר שמור באנשי קשר")
        if (lastReserved != null && now - lastReserved < rules.cooldownHours * 3_600_000L) return Eligibility(number, "מניעת הודעה חוזרת לפי זמן ההמתנה שהוגדר")
        return Eligibility(number)
    }
}
data class UiProof(val packageName: String, val recipient: String?, val editorText: String?,
    val editorCount: Int, val recipientCount: Int, val sendCount: Int, val sendEnabled: Boolean, val locked: Boolean)
object SendGuard {
    fun permits(requestPackage: String, number: String, text: String, expiresAt: Long, now: Long,
                alreadyClaimed: Boolean, proof: UiProof): Boolean =
        !alreadyClaimed && now < expiresAt && !proof.locked && requestPackage in setOf(WA, BUSINESS) &&
            proof.packageName == requestPackage && proof.recipientCount == 1 &&
            Numbers.normalize(proof.recipient) == number && proof.editorCount == 1 &&
            proof.editorText == text && proof.sendCount == 1 && proof.sendEnabled
}
