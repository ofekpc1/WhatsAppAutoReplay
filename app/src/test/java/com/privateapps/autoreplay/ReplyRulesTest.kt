package com.privateapps.autoreplay

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReplyRulesTest {
    private val now=1_800_000_000_000L
    private val missed=CallRecord(101,now-2000,3,"0502345678",1)
    private fun decide(call: CallRecord=missed,rules: ReplyRules=ReplyRules(),last: Long?=null)=
        Policy.eligible(call,now-3_000_000,100,false,true,false,false,false,last,now,rules)
    @Test fun defaultsKeepAnsweredAndOutgoingOff() {
        assertNotNull(decide(missed.copy(type=1,durationSeconds=1)).reason)
        assertNotNull(decide(missed.copy(type=2,durationSeconds=1)).reason)
    }
    @Test fun optInAcceptsCompletedIncoming() {assertNull(decide(missed.copy(type=1,durationSeconds=1),ReplyRules(incoming=true)).reason)}
    @Test fun optInAcceptsCompletedOutgoing() {assertNull(decide(missed.copy(type=2,durationSeconds=1),ReplyRules(outgoing=true)).reason)}
    @Test fun outgoingWithoutDurationRejected() {assertNotNull(decide(missed.copy(type=2),ReplyRules(outgoing=true)).reason)}
    @Test fun longConversationFreshAtEnd() {assertNull(decide(missed.copy(type=1,date=now-1_201_000,durationSeconds=1200),ReplyRules(incoming=true)).reason)}
    @Test fun missedCanBeDisabled() {assertNotNull(decide(rules=ReplyRules(missed=false)).reason)}
    @Test fun cooldownCanBeDisabledForNewCalls() {assertNull(decide(rules=ReplyRules(cooldownHours=0),last=now-1).reason)}
    @Test fun thirtyDayCooldown() {assertNotNull(decide(rules=ReplyRules(cooldownHours=720),last=now-29*DAY).reason)}
    @Test fun exactCooldownBoundaryAllowed() {assertNull(decide(rules=ReplyRules(cooldownHours=720),last=now-30*DAY).reason)}
    @Test fun exclusionsNormalize() {assertNotNull(decide(rules=ReplyRules(excluded="+972 50-2345678")).reason)}
    @Test fun foreignNumberFiltered() {assertNotNull(decide(missed.copy(number="+14155552671"),ReplyRules(international=false)).reason)}
    @Test fun invalidHoursAndInvalidNumbersRejected() {
        assertFalse(ReplyRules(hours=List(7){"23:00-11:00"}).valid())
        assertFalse(ReplyRules(excluded="not a number").valid())
        assertFalse(ReplyRules(timeoutSeconds=0).valid())
    }
    @Test fun openingAndClosingBoundaries() {
        val r=ReplyRules();val zone=ZoneId.of("UTC")
        assertTrue(r.isOpen(Instant.parse("2026-10-04T11:00:00Z").toEpochMilli(),zone))
        assertFalse(r.isOpen(Instant.parse("2026-10-04T23:00:00Z").toEpochMilli(),zone))
        assertFalse(r.isOpen(Instant.parse("2026-10-03T12:00:00Z").toEpochMilli(),zone))
    }
    @Test fun separateTemplatesAndClosedDay() {
        val r=ReplyRules(outsideEnabled=true,hours=List(7){""})
        assertEquals(r.outsideMessage,r.message(missed,"normal"))
        assertEquals(r.incomingMessage,r.message(missed.copy(type=1),"normal"))
        assertEquals(r.outgoingMessage,r.message(missed.copy(type=2),"normal"))
        assertEquals("normal",r.copy(outsideEnabled=false).message(missed,"normal"))
    }
}
