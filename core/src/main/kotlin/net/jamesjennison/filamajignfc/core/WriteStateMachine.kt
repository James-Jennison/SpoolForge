package net.jamesjennison.filamajignfc.core

enum class WritePhase { DRAFT, AWAITING_TAG, INSPECTING, NEEDS_OVERWRITE_CONSENT, WRITING, VERIFYING, VERIFICATION_PENDING, VERIFIED, COMPLETED, CANCELLED, REJECTED, FAILED_BEFORE_WRITE, WRITE_OUTCOME_UNKNOWN, UNRESOLVED_ARCHIVED }
class TagRecord(val tnf:Int,val mime:String,payload:ByteArray,id:ByteArray=byteArrayOf()){
    private val frozen=payload.copyOf();private val frozenId=id.copyOf()
    val payload:ByteArray get()=frozen.copyOf();val id:ByteArray get()=frozenId.copyOf()
    internal fun payloadUnsafe()=frozen;internal fun idUnsafe()=frozenId
    override fun equals(other:Any?)=other is TagRecord&&tnf==other.tnf&&mime==other.mime&&frozen.contentEquals(other.frozen)&&frozenId.contentEquals(other.frozenId)
    override fun hashCode()=31*(31*(31+tnf)+mime.hashCode())+frozen.contentHashCode()+31*frozenId.contentHashCode()
}
class TagSnapshot(uid:ByteArray,val writable:Boolean,val maxNdefSize:Int,records:List<TagRecord>){private val frozenUid=uid.copyOf();val uid:ByteArray get()=frozenUid.copyOf();val records=records.map{TagRecord(it.tnf,it.mime,it.payload,it.id)};internal fun uidUnsafe()=frozenUid}
data class WriteState(val phase: WritePhase, val intent: OpenSpoolIntent? = null, private val frozenUid: ByteArray? = null, val detail: String = "", val tagNumber: Int = 1) { val uid:ByteArray? get()=frozenUid?.copyOf(); internal fun uidUnsafe()=frozenUid }

class WriteStateMachine {
    var state = WriteState(WritePhase.DRAFT); private set
    private var approvedExistingFingerprint:ByteArray?=null
    private var expectedPayload:ByteArray?=null
    private var expectedRecords:List<TagRecord>?=null
    private var excludedUid:ByteArray?=null
    fun arm(intent: OpenSpoolIntent) { require(state.phase in setOf(WritePhase.DRAFT,WritePhase.COMPLETED,WritePhase.CANCELLED,WritePhase.REJECTED,WritePhase.FAILED_BEFORE_WRITE,WritePhase.UNRESOLVED_ARCHIVED));approvedExistingFingerprint=null;pendingConsentFingerprint=null;expectedPayload=null;expectedRecords=intent.ndefRecords;excludedUid=null;state=WriteState(WritePhase.AWAITING_TAG,intent,detail="Tap a compatible ${tagLabel(intent.codecId)} tag") }
    fun armSecondTag() { require(state.phase==WritePhase.VERIFIED&&state.tagNumber==1);val intent=state.intent?:error("The original write intent is no longer available");excludedUid=state.uid;approvedExistingFingerprint=null;pendingConsentFingerprint=null;expectedPayload=null;state=WriteState(WritePhase.AWAITING_TAG,intent,detail="Tap a different compatible ${tagLabel(intent.codecId)} tag for the other side",tagNumber=2) }
    fun finishWithOneTag() { require((state.phase==WritePhase.VERIFIED&&state.tagNumber==1)||(state.tagNumber==2&&state.phase in setOf(WritePhase.AWAITING_TAG,WritePhase.INSPECTING,WritePhase.NEEDS_OVERWRITE_CONSENT,WritePhase.REJECTED,WritePhase.FAILED_BEFORE_WRITE)));approvedExistingFingerprint=null;pendingConsentFingerprint=null;expectedPayload=null;excludedUid=null;state=WriteState(WritePhase.COMPLETED,detail="Completed with one verified tag") }
    fun inspected(tag: TagSnapshot, ndefMessageSize: Int) {
        val intent=state.intent ?: error("No intent"); require(state.phase==WritePhase.AWAITING_TAG || state.phase==WritePhase.INSPECTING || state.phase==WritePhase.NEEDS_OVERWRITE_CONSENT)
        if(state.tagNumber==2&&excludedUid?.contentEquals(tag.uid)==true){state=state.copy(phase=WritePhase.AWAITING_TAG,frozenUid=null,detail="That is the first tag. Present a different NTAG215 for side two.");return}
        state=state.copy(phase=WritePhase.INSPECTING,frozenUid=tag.uid,detail="Inspecting writable capacity")
        if(!tag.writable) { state=state.copy(phase=WritePhase.REJECTED,detail="Tag is read-only"); return }
        if(ndefMessageSize>tag.maxNdefSize) { state=state.copy(phase=WritePhase.REJECTED,detail="Message needs $ndefMessageSize bytes; tag reports ${tag.maxNdefSize}"); return }
        val fingerprint=tagFingerprint(tag)
        if(tag.records.isNotEmpty()&&(approvedExistingFingerprint==null||!fingerprint.contentEquals(approvedExistingFingerprint))) { approvedExistingFingerprint=null;pendingConsentFingerprint=fingerprint;state=state.copy(phase=WritePhase.NEEDS_OVERWRITE_CONSENT,detail="Existing content requires approval bound to this tag and content");return }
        pendingConsentFingerprint=null
        expectedPayload=intent.payload;expectedRecords=intent.ndefRecords
        state=state.copy(phase=WritePhase.WRITING,detail="Keep tag in place")
    }
    fun approveOverwrite(){require(state.phase==WritePhase.NEEDS_OVERWRITE_CONSENT);approvedExistingFingerprint=pendingConsentFingerprint?.copyOf()?:error("No inspected content");state=state.copy(phase=WritePhase.AWAITING_TAG,detail="Approval recorded. Re-tap the same unchanged tag.")}
    fun writeReturned() { require(state.phase==WritePhase.WRITING); state=state.copy(phase=WritePhase.VERIFYING,detail="Remove and re-present only if asked") }
    fun writeInterrupted(reason:String) { require(state.phase==WritePhase.WRITING||state.phase==WritePhase.VERIFYING); state=state.copy(phase=WritePhase.WRITE_OUTCOME_UNKNOWN,detail="$reason. Re-tap for read-only reconciliation; no rewrite will occur.") }
    fun verificationDeferred(reason:String) { require(state.phase==WritePhase.VERIFYING);state=state.copy(phase=WritePhase.VERIFICATION_PENDING,detail="$reason. The write call completed; remove and re-tap this tag for read-only verification. No rewrite will occur.") }
    fun writeFailedBeforeCall(reason:String) { require(state.phase==WritePhase.WRITING); state=state.copy(phase=WritePhase.FAILED_BEFORE_WRITE,detail=reason) }
    fun rejectBeforeWrite(reason:String){require(state.phase in setOf(WritePhase.AWAITING_TAG,WritePhase.INSPECTING,WritePhase.NEEDS_OVERWRITE_CONSENT));state=state.copy(phase=WritePhase.REJECTED,detail=reason)}
    fun reconciled(tag: TagSnapshot) {
        require(state.phase in setOf(WritePhase.VERIFYING,WritePhase.VERIFICATION_PENDING,WritePhase.WRITE_OUTCOME_UNKNOWN))
        val incomingPhase=state.phase
        val intendedUid=state.uid
        if(intendedUid==null||!intendedUid.contentEquals(tag.uid)) { state=state.copy(phase=if(incomingPhase==WritePhase.VERIFICATION_PENDING) WritePhase.VERIFICATION_PENDING else WritePhase.WRITE_OUTCOME_UNKNOWN,detail="A different tag was presented. Re-tap the tag whose write is being verified."); return }
        val expected=state.intent?.ndefRecords?:expectedRecords
        val matches=expected!=null&&recordsMatch(expected,tag.records)
        state=when {
            matches -> state.copy(phase=WritePhase.VERIFIED,detail=if(state.tagNumber==2) "Second tag verified with the same frozen spool record" else "First tag verified with the frozen spool record")
            tag.records.isEmpty()&&incomingPhase!=WritePhase.WRITE_OUTCOME_UNKNOWN -> state.copy(phase=WritePhase.VERIFICATION_PENDING,detail="The write call completed, but Android has not returned a verification read yet. Remove and re-tap this tag; no rewrite will occur.")
            else -> state.copy(phase=WritePhase.WRITE_OUTCOME_UNKNOWN,detail=if(tag.records.isEmpty()) "No NDEF message returned by this read; outcome remains unknown; no automatic rewrite" else "Fresh NDEF message does not match; no automatic rewrite")
        }
    }
    fun restoreUnknown(uid:ByteArray,payload:ByteArray,tagNumber:Int=1,records:List<TagRecord>?=null){require(state.phase==WritePhase.DRAFT);require(tagNumber in 1..2);expectedPayload=payload.copyOf();expectedRecords=records?.map{TagRecord(it.tnf,it.mime,it.payload,it.id)}?:listOf(TagRecord(2,OpenSpoolCodec.MIME,payload));state=WriteState(WritePhase.WRITE_OUTCOME_UNKNOWN,null,uid.copyOf(),"A prior write may have reached the tag. Re-tap for read-only reconciliation.",tagNumber)}
    fun restorePending(uid:ByteArray,payload:ByteArray,tagNumber:Int=1,records:List<TagRecord>?=null){require(state.phase==WritePhase.DRAFT);require(tagNumber in 1..2);expectedPayload=payload.copyOf();expectedRecords=records?.map{TagRecord(it.tnf,it.mime,it.payload,it.id)}?:listOf(TagRecord(2,OpenSpoolCodec.MIME,payload));state=WriteState(WritePhase.VERIFICATION_PENDING,null,uid.copyOf(),"A write call completed before restart. Re-tap the same tag for read-only verification.",tagNumber)}
    fun reconciliationReadFailed(reason:String){require(state.phase in setOf(WritePhase.VERIFICATION_PENDING,WritePhase.WRITE_OUTCOME_UNKNOWN));state=state.copy(detail=if(state.phase==WritePhase.VERIFICATION_PENDING) "$reason. Verification is still pending; re-tap the same tag. No rewrite will occur." else "$reason. The outcome remains unknown; re-tap the same tag for read-only reconciliation.")}
    fun archiveUnknown(){require(state.phase==WritePhase.WRITE_OUTCOME_UNKNOWN||state.phase==WritePhase.VERIFICATION_PENDING);state=WriteState(WritePhase.UNRESOLVED_ARCHIVED,detail="Unresolved write archived without claiming cancellation or verification")}
    fun cancel() { if(state.phase==WritePhase.WRITING||state.phase==WritePhase.VERIFYING||state.phase==WritePhase.WRITE_OUTCOME_UNKNOWN||state.phase==WritePhase.VERIFICATION_PENDING) { if(state.phase==WritePhase.WRITING||state.phase==WritePhase.VERIFYING)writeInterrupted("Operation interrupted") } else state=state.copy(phase=WritePhase.CANCELLED,detail="Cancelled before writing") }

    private fun recordsMatch(expected:List<TagRecord>,actual:List<TagRecord>):Boolean {
        if(expected.size!=actual.size)return false
        return expected.zip(actual).all { (a,b) ->
            a.tnf==b.tnf&&a.mime==b.mime&&a.idUnsafe().contentEquals(b.idUnsafe())&&
                if(a.tnf==2&&a.mime==OpenSpoolCodec.MIME)semanticPayloadsMatch(a.payloadUnsafe(),b.payloadUnsafe()) else a.payloadUnsafe().contentEquals(b.payloadUnsafe())
        }
    }
    private fun semanticPayloadsMatch(a:ByteArray,b:ByteArray):Boolean{val x=OpenSpoolCodec.decode(a) as? DecodeResult.Supported?:return false;val y=OpenSpoolCodec.decode(b) as? DecodeResult.Supported?:return false;return x.values==y.values}
    private fun tagFingerprint(tag:TagSnapshot)=tagFingerprint(tag.uidUnsafe(),tag.records)
    private fun tagFingerprint(uid:ByteArray,records:List<TagRecord>):ByteArray {val digest=java.security.MessageDigest.getInstance("SHA-256");fun sized(bytes:ByteArray){digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)};sized(uid);digest.update(java.nio.ByteBuffer.allocate(4).putInt(records.size).array());records.forEach{digest.update(java.nio.ByteBuffer.allocate(4).putInt(it.tnf).array());sized(it.mime.encodeToByteArray());sized(it.idUnsafe());sized(it.payloadUnsafe())};return digest.digest()}
    private var pendingConsentFingerprint:ByteArray?=null
    private fun tagLabel(codecId:String)=runCatching{TagCodecRegistry.require(codecId).format.transport}.getOrNull()?.let{when(it){TagTransport.NDEF->"NDEF";TagTransport.NTAG_RAW->"NTAG21x";TagTransport.MIFARE_CLASSIC_QIDI,TagTransport.MIFARE_CLASSIC_CFS->"MIFARE Classic 1K"}}?:"NFC"
}
