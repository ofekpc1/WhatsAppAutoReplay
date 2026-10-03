package com.privateapps.autoreplay

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    private val now=1_800_000_000_000L
    private fun call(type:Int=3,number:String?="0502345678",presentation:Int=1,date:Long=now-2000,id:Long=101)=CallRecord(id,date,type,number,presentation)
    private fun decide(c:CallRecord=call(),seen:Boolean=false,permission:Boolean=true,exclude:Boolean=false,
                       contactsPermission:Boolean=true,contact:Boolean=false,last:Long?=null)=
        Policy.eligible(c,now-60_000,100,seen,permission,exclude,contactsPermission,contact,last,now)
    @Test fun missedCallAccepted() {assertEquals("+972502345678",decide().number);assertNull(decide().reason)}
    @Test fun answeredCallSkipped() {assertNotNull(decide(call(type=1)).reason)}
    @Test fun outgoingCallSkipped() {assertNotNull(decide(call(type=2)).reason)}
    @Test fun rejectedCallSkipped() {assertNotNull(decide(call(type=5)).reason)}
    @Test fun blockedCallSkipped() {assertNotNull(decide(call(type=6)).reason)}
    @Test fun repeatCallerSkipped() {assertNotNull(decide(last=now-1000).reason)}
    @Test fun cooldownExpiresAt24Hours() {assertNull(decide(last=now-DAY).reason)}
    @Test fun backwardsClockFailsClosed() {assertNotNull(decide(last=now+1000).reason)}
    @Test fun hiddenNumberSkipped() {assertNotNull(decide(call(presentation=2)).reason)}
    @Test fun nullNumberSkipped() {assertNotNull(decide(call(number=null)).reason)}
    @Test fun invalidNumberSkipped() {assertNotNull(decide(call(number="-1")).reason)}
    @Test fun missingPermissionSkipped() {assertNotNull(decide(permission=false).reason)}
    @Test fun duplicateCallSkipped() {assertNotNull(decide(seen=true).reason)}
    @Test fun oldCallSkippedEvenIfInsertedLate() {assertNotNull(decide(call(date=now-120_000)).reason)}
    @Test fun baselineCallSkipped() {assertNotNull(decide(call(id=100)).reason)}
    @Test fun noCatchupAfterLongDelay() {assertNotNull(Policy.eligible(call(date=now-700_000),now-800_000,100,false,true,false,false,false,null,now).reason)}
    @Test fun contactsSkippedWhenSelected() {assertNotNull(decide(exclude=true,contact=true).reason)}
    @Test fun contactsPermissionMissingFailsClosed() {assertNotNull(decide(exclude=true,contactsPermission=false).reason)}
    @Test fun contactsPermissionNotNeededWithoutFilter() {assertNull(decide(contactsPermission=false).reason)}
    @Test fun internationalAndLocalNormalizeIdentically() {assertEquals(Numbers.normalize("050-234-5678"),Numbers.normalize("+972 50 234 5678"))}
    @Test fun doubleZeroPrefixSupported() {assertEquals("+972502345678",Numbers.normalize("00972502345678"))}
    @Test fun shortcodesRejected() {assertNull(Numbers.normalize("100"));assertNull(Numbers.normalize("*1234"))}
    @Test fun contactNameNotPhone() {assertNull(Numbers.normalize("משה 0502345678"))}
    private fun proof()=UiProof(WA,"+972 50-234-5678",DEFAULT_MESSAGE,1,1,1,true,false)
    private fun allows(p:UiProof=proof(),expired:Boolean=false,claimed:Boolean=false)=SendGuard.permits(WA,"+972502345678",DEFAULT_MESSAGE,now+1000,if(expired) now+1000 else now,claimed,p)
    @Test fun exactVerifiedChatPermitted() {assertTrue(allows())}
    @Test fun lockedScreenPreventsClick() {assertFalse(allows(proof().copy(locked=true)))}
    @Test fun wrongWhatsappPackageRejected() {assertFalse(allows(proof().copy(packageName=BUSINESS)))}
    @Test fun wrongRecipientRejected() {assertFalse(allows(proof().copy(recipient="+972541234567")))}
    @Test fun recipientNameWithoutPhoneRejected() {assertFalse(allows(proof().copy(recipient="לקוח")))}
    @Test fun changedOrAppendedTextRejected() {assertFalse(allows(proof().copy(editorText=DEFAULT_MESSAGE+" ")))}
    @Test fun ambiguousEditorsRejected() {assertFalse(allows(proof().copy(editorCount=2)))}
    @Test fun ambiguousRecipientsRejected() {assertFalse(allows(proof().copy(recipientCount=2)))}
    @Test fun unavailableRecipientHasNoValidSendUi() {assertFalse(allows(proof().copy(sendCount=0,editorCount=0)))}
    @Test fun disabledSendRejected() {assertFalse(allows(proof().copy(sendEnabled=false)))}
    @Test fun expiredRequestCannotClick() {assertFalse(allows(expired=true))}
    @Test fun alreadyClaimedRequestCannotClick() {assertFalse(allows(claimed=true))}
}
