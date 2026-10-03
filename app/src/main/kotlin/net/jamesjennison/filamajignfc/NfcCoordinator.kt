package net.jamesjennison.filamajignfc

import android.content.Context
import android.nfc.*
import android.nfc.tech.Ndef
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcV
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.*
import net.jamesjennison.filamajignfc.core.*
import java.util.concurrent.Executors

private const val ELEGOO_USER_OFFSET = ElegooCanvasTagCodec.USER_AREA_OFFSET

data class WriteBindingContext(val spoolId: String, val codecId: String)
data class VerifiedTagWrite(val context: WriteBindingContext, val uid: ByteArray, val payload: ByteArray, val bindingOrder: Int)
internal enum class BindingPersistence { NOT_REQUIRED, SAVED, RETRY_REQUIRED }

class NfcCoordinator(
    context:Context,
    private val io: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(),
    private val onVerified: ((VerifiedTagWrite) -> Unit)? = null,
) {
    private val machine=WriteStateMachine(); private val main=Handler(Looper.getMainLooper())
    @Volatile private var disposed = false
    private val journal=WriteJournal(context)
    private val diagnostics=context.getSharedPreferences("nfc-read-diagnostics",Context.MODE_PRIVATE)
    var readDiagnostic by mutableStateOf<String?>(null); private set
    var state by mutableStateOf(machine.state); private set
    var lastRead by mutableStateOf<DecodeResult?>(null); private set
    var bindingStatus by mutableStateOf<String?>(null); private set
    var detectedTagType by mutableStateOf<String?>(null); private set
    var unresolvedArchiveCount by mutableIntStateOf(journal.archiveCount()); private set
    var cfsFinalizationResumeAvailable by mutableStateOf(false); private set
    private var bindingContext: WriteBindingContext? = null
    private var activeCodecId:String?=null
    private var cfsTrailerVerificationRequired=false
    private var cfsFinalizationResumeApproved=false
    init { journal.load()?.let{entry->bindingContext=entry.bindingContext;activeCodecId=entry.codecId;cfsTrailerVerificationRequired=entry.cfsTrailerVerificationRequired;val records=entry.expectedMessage?.let(::recordsFromMessageBytes)?:entry.codecId?.let{listOf(TagRecord(-1,it,entry.payload))};if(entry.verificationPending)machine.restorePending(entry.uid,entry.payload,entry.tagNumber,records)else machine.restoreUnknown(entry.uid,entry.payload,entry.tagNumber,records);state=machine.state} }
    internal fun archivedWrites() = journal.archives()
    fun arm(intent:OpenSpoolIntent, binding: WriteBindingContext? = null)=dispatch{bindingContext=binding;activeCodecId=intent.codecId;cfsTrailerVerificationRequired=false;cfsFinalizationResumeApproved=false;post{cfsFinalizationResumeAvailable=false;detectedTagType=null};bindingStatus=null;machine.arm(intent)}
    fun armSecondTag()=dispatch{machine.armSecondTag()}
    fun finishWithOneTag()=dispatch{machine.finishWithOneTag();journal.clear();bindingContext=null}
    fun approveOverwrite()=dispatch{machine.approveOverwrite()}
    fun approveCfsFinalizationResume()=dispatch{check(cfsFinalizationResumeAvailable){"No recoverable CFS finalization is available"};cfsFinalizationResumeApproved=true;post{cfsFinalizationResumeAvailable=false;bindingStatus="CFS trailer completion approved. Re-tap the same tag; only the pending trailer will be written."}}
    fun cancel()=dispatch{cfsFinalizationResumeApproved=false;post{cfsFinalizationResumeAvailable=false};machine.cancel();persistOrClear()}
    fun archiveUnknown()=dispatch{val s=machine.state;val uid=s.uid?:error("No unresolved tag");val payload=journal.activePayload()?:error("No unresolved payload");check(journal.archive(uid,payload)){"Could not archive unresolved write"};cfsFinalizationResumeApproved=false;machine.archiveUnknown();post{cfsFinalizationResumeAvailable=false;unresolvedArchiveCount=journal.archiveCount()}}
    fun shutdown(){disposed = true; io.shutdown()}
    private fun post(block: () -> Unit) { main.post { if (!disposed) block() } }
    private fun execute(block: () -> Unit) { try { io.execute { if (!disposed) block() } } catch (_: java.util.concurrent.RejectedExecutionException) { /* Callback arrived after ViewModel disposal. */ } }
    fun onTag(tag:Tag){ execute { handle(tag) } }
    private fun dispatch(block:()->Unit){execute{runCatching(block).onFailure{e->post{state=state.copy(detail=e.message?:"Operation rejected")}};publish()}}
    private fun publish(){val next=machine.state;post{state=next}}
    private fun persistOrClear(){val s=machine.state;when(s.phase){WritePhase.WRITING,WritePhase.VERIFYING,WritePhase.VERIFICATION_PENDING,WritePhase.WRITE_OUTCOME_UNKNOWN->{val uid=s.uid;val payload=s.intent?.payload?:journal.activePayload();val codecId=s.intent?.codecId?:activeCodecId?:journal.load()?.codecId;val message=s.intent?.takeIf{TagCodecRegistry.require(it.codecId).format.transport==TagTransport.NDEF}?.let(::messageForIntent)?.toByteArray()?:journal.activeMessage();if(uid!=null&&payload!=null)journal.save(uid,payload,s.phase==WritePhase.VERIFICATION_PENDING,bindingContext,s.tagNumber,message,codecId,cfsTrailerVerificationRequired)}else->Unit}}
    private fun verifiedWrite(): VerifiedTagWrite? {
        val s=machine.state
        val binding=bindingContext ?: return null
        val uid=s.uid ?: return null
        val payload=s.intent?.payload ?: journal.activePayload() ?: return null
        return VerifiedTagWrite(binding,uid,payload,s.tagNumber)
    }
    private fun reconcile(snapshot: TagSnapshot) {
        val before=machine.state.phase
        machine.reconciled(snapshot)
        if(before!=WritePhase.VERIFIED&&machine.state.phase==WritePhase.VERIFIED) {
            val result=finalizeVerifiedBinding(verifiedWrite(),onVerified,journal)
            when(result.persistence) {
                BindingPersistence.SAVED -> post { bindingStatus="Tag ${machine.state.tagNumber} is verified and bound to this spool" }
                BindingPersistence.RETRY_REQUIRED -> post { bindingStatus="Tag verified, but its local spool binding could not be saved: ${result.errorMessage}. Restart and re-tap this tag for read-only retry." }
                BindingPersistence.NOT_REQUIRED -> Unit
            }
        } else persistOrClear()
    }
    private fun awaitingWrite()=machine.state.phase in setOf(WritePhase.AWAITING_TAG,WritePhase.INSPECTING,WritePhase.NEEDS_OVERWRITE_CONSENT)
    private fun handle(tag:Tag){
        val codecId=machine.state.intent?.codecId?:activeCodecId
        val transport=codecId?.let{runCatching{TagCodecRegistry.require(it).format.transport}.getOrNull()}
        if(transport==TagTransport.NTAG_RAW){handleRawNtag(tag,checkNotNull(codecId));return}
        if(transport==TagTransport.MIFARE_CLASSIC_QIDI){handleQidi(tag,checkNotNull(codecId));return}
        if(transport==TagTransport.MIFARE_CLASSIC_CFS){handleCfs(tag,checkNotNull(codecId));return}
        val ndef=Ndef.get(tag) ?: run {
            if(!awaitingWrite()&&MifareUltralight.get(tag)!=null){handleRawNtag(tag,AnycubicAceTagCodec.format.id);return}
            if(!awaitingWrite()&&MifareClassic.get(tag)!=null){handleClassicDiscovery(tag);return}
            if(awaitingWrite()){machine.rejectBeforeWrite("Unsupported or unformatted tag. Automatic formatting is disabled.");publish()}else post{lastRead=DecodeResult.Rejected("Unsupported or unformatted tag. Automatic formatting is disabled.")};return
        }
        val completedReads = mutableListOf<Pair<String, NdefMessage?>>()
        try {
            ndef.connect()
            val current=ndef.ndefMessage // active RF read; never cachedNdefMessage
            completedReads.add("inspection" to current)
            val records=current?.records?.map{TagRecord(it.tnf.toInt(),it.type.toString(Charsets.US_ASCII),it.payload,it.id)}?:emptyList()
            val snapshot=TagSnapshot(tag.id.copyOf(),ndef.isWritable,ndef.maxSize,records)
            if(machine.state.phase in setOf(WritePhase.VERIFYING,WritePhase.VERIFICATION_PENDING,WritePhase.WRITE_OUTCOME_UNKNOWN)){
                reconcile(snapshot);post{lastRead=decodeSingleTag(current)};publish();return
            }
            if(!awaitingWrite()){post{lastRead=decodeSingleTag(current)};return}
            val intent=machine.state.intent ?: return
            if(intent.codecId==OpenPrintTagWriteCodec.format.id&&NfcV.get(tag)==null){machine.rejectBeforeWrite("OpenPrintTag requires an NFC-V / ISO 15693 tag such as ICODE SLIX2");publish();return}
            if(intent.codecId!=OpenPrintTagWriteCodec.format.id&&tag.techList.contains(NfcV::class.java.name)){machine.rejectBeforeWrite("${TagCodecRegistry.require(intent.codecId).format.displayName} requires an NFC-A tag");publish();return}
            val message=messageForIntent(intent)
            val recognizedTypes=setOf(OpenSpoolCodec.MIME,OpenTag3dV2Adapter.format.mimeType,OpenPrintTagAdapter.format.mimeType)
            if(records.any{it.tnf!=NdefRecord.TNF_MIME_MEDIA.toInt()||it.mime.lowercase() !in recognizedTypes}||records.groupBy{it.mime.lowercase()}.any{it.value.size>1}){machine.rejectBeforeWrite("Existing tag content is ambiguous and will not be overwritten");publish();return}
            if(records.any{it.mime.lowercase()==OpenPrintTagAdapter.format.mimeType}){machine.rejectBeforeWrite("Existing OpenPrintTag records are read-only because safe updates must preserve their meta and protected regions. Use a blank, NDEF-formatted, unprotected NFC-V tag for initialization.");publish();return}
            val unsupported=records.map{record->when(record.mime.lowercase()){OpenSpoolCodec.MIME->OpenSpoolCodec.decode(record.payload);else->ExternalTagRegistry.decode(record.mime.lowercase(),record.payload)}}.firstOrNull{it !is DecodeResult.Supported||it.warningsOrEmpty().isNotEmpty()}
            if(unsupported!=null){val reason=when(unsupported){is DecodeResult.ReadOnly->unsupported.reason;is DecodeResult.Rejected->unsupported.reason;else->"Unsupported filament data"};machine.rejectBeforeWrite("Existing filament content is read-only: $reason");publish();return}
            machine.inspected(snapshot,message.toByteArray().size);publish()
            if(machine.state.phase!=WritePhase.WRITING)return
            val writeUid=machine.state.uid;val writePayload=machine.state.intent?.payload
            if(writeUid==null||writePayload==null||!journal.save(writeUid,writePayload,false,bindingContext,machine.state.tagNumber,message.toByteArray(),intent.codecId)){machine.writeFailedBeforeCall("Could not durably journal the write; no write was attempted");publish();return}
            try { ndef.writeNdefMessage(message) } catch(e:Exception){ machine.writeInterrupted("Write did not complete: ${e.javaClass.simpleName}");publish();return }
            machine.writeReturned();persistOrClear();publish()
            // Every attempt is an uncached active RF read. A transient null after a
            // successful write is retried without ever invoking the write call again.
            val fresh=readFreshNdefWithRetries { ndef.ndefMessage }
            completedReads.add("post-write" to fresh)
            val freshRecords=fresh?.records?.map{TagRecord(it.tnf.toInt(),it.type.toString(Charsets.US_ASCII),it.payload,it.id)}?:emptyList()
            reconcile(TagSnapshot(tag.id.copyOf(),ndef.isWritable,ndef.maxSize,freshRecords));post{lastRead=decodeSingleTag(fresh)};publish()
        } catch(e:Exception){
            if(machine.state.phase==WritePhase.WRITING)machine.writeInterrupted("Tag connection lost during write")
            else if(machine.state.phase==WritePhase.VERIFYING)machine.verificationDeferred("Android lost the tag connection during verification")
            else if(machine.state.phase==WritePhase.VERIFICATION_PENDING||machine.state.phase==WritePhase.WRITE_OUTCOME_UNKNOWN)machine.reconciliationReadFailed("NFC reconciliation read failed: ${e.javaClass.simpleName}")
            else if(awaitingWrite())machine.rejectBeforeWrite("NFC read failed: ${e.javaClass.simpleName}") else post{lastRead=DecodeResult.Rejected("NFC read failed: ${e.javaClass.simpleName}")}
            persistOrClear()
            publish()
        } finally {
            runCatching { ndef.close() }
            // Hashing and diagnostic disk I/O run only after RF handling has settled.
            completedReads.forEach { (stage, message) -> runCatching { recordRead(tag, ndef, message, stage) } }
        }
    }
    private fun handleRawNtag(tag:Tag,codecId:String){
        val tech=MifareUltralight.get(tag)?:run{if(awaitingWrite()){machine.rejectBeforeWrite("${TagCodecRegistry.require(codecId).format.displayName} requires an NTAG213/215/216-compatible NFC-A tag");publish()}else post{lastRead=DecodeResult.Rejected("Unsupported raw NTAG")};return}
        try{
            tech.connect()
            val canvasMode=codecId==ElegooCanvasTagCodec.format.id
            val inspection=if(canvasMode)inspectCanvasTag(tech)else null
            val currentUserArea=readNtagUserPages(tech,if(canvasMode)129 else 39)
            val current=if(canvasMode)currentUserArea.copyOfRange(ELEGOO_USER_OFFSET,ELEGOO_USER_OFFSET+ElegooCanvasTagCodec.PAYLOAD_BYTES)else currentUserArea
            val currentRecords=if(current.all{it==0.toByte()})emptyList()else listOf(TagRecord(-1,codecId,current))
            val snapshot=TagSnapshot(tag.id,true,inspection?.writableBytes?:current.size,currentRecords)
            if(machine.state.phase in setOf(WritePhase.VERIFYING,WritePhase.VERIFICATION_PENDING,WritePhase.WRITE_OUTCOME_UNKNOWN)){reconcile(snapshot);post{lastRead=TagCodecRegistry.require(codecId).decode(current)};publish();return}
            val competingCanvasData=canvasMode&&ElegooCanvasTagCodec.hasCompetingUserData(currentUserArea)
            if(!awaitingWrite()){post{lastRead=if(competingCanvasData)DecodeResult.Rejected("This tag also contains data outside CANVAS pages 16-31; it is not a one-format CANVAS tag")else decodeRawNtag(current)};return}
            if(competingCanvasData){machine.rejectBeforeWrite("CANVAS one-tag mode requires a blank NTAG215 outside pages 16-31. Existing data was preserved and no write was attempted");publish();return}
            val intent=machine.state.intent?:return
            val existing=if(canvasMode)ElegooCanvasTagCodec.decode(current)else decodeRawNtag(current)
            if(currentRecords.isNotEmpty()&&existing !is DecodeResult.Supported){machine.rejectBeforeWrite("Existing raw NTAG content in the target pages is not a fully supported filament format and will not be overwritten");publish();return}
            machine.inspected(snapshot,intent.payload.size);publish();if(machine.state.phase!=WritePhase.WRITING)return
            val uid=machine.state.uid?:return
            if(!journal.save(uid,intent.payload,false,bindingContext,machine.state.tagNumber,null,codecId)){machine.writeFailedBeforeCall("Could not durably journal the raw tag write; no write was attempted");publish();return}
            val firstPage=if(canvasMode)ElegooCanvasTagCodec.TARGET_PAGE else 4
            try{intent.payload.toList().chunked(4).forEachIndexed{i,bytes->tech.writePage(firstPage+i,bytes.toByteArray())}}catch(e:Exception){machine.writeInterrupted("Raw CANVAS page write did not complete; pages 16-31 may be partially modified: ${e.javaClass.simpleName}");persistOrClear();publish();return}
            machine.writeReturned();persistOrClear();publish()
            val freshUserArea=readNtagUserPages(tech,if(canvasMode)129 else 39)
            val fresh=if(canvasMode)freshUserArea.copyOfRange(ELEGOO_USER_OFFSET,ELEGOO_USER_OFFSET+ElegooCanvasTagCodec.PAYLOAD_BYTES)else freshUserArea
            reconcile(TagSnapshot(tag.id,true,inspection?.writableBytes?:fresh.size,listOf(TagRecord(-1,codecId,fresh))));post{lastRead=TagCodecRegistry.require(codecId).decode(fresh)};publish()
        }catch(e:Exception){handleTransportFailure(e,"NTAG")}finally{runCatching{tech.close()}}
    }

    private fun inspectCanvasTag(tech:MifareUltralight):Ntag215WriteInspection {
        val version=tech.transceive(byteArrayOf(0x60))
        val control=tech.readPages(130)
        if(control.size<8)throw java.io.IOException("NTAG215 lock/configuration read was incomplete")
        val inspection=Ntag215WriteValidator.inspect(version,control.copyOfRange(0,4),control.copyOfRange(4,8),ElegooCanvasTagCodec.PAYLOAD_BYTES)
        post{detectedTagType="Detected ${inspection.product.displayName} · NFC-A · ${inspection.writableBytes} writable user bytes"}
        return inspection
    }
    private fun handleQidi(tag:Tag,codecId:String){
        val tech=MifareClassic.get(tag)?:run{if(awaitingWrite()){machine.rejectBeforeWrite("QIDI Box requires a phone-supported MIFARE Classic 1K tag");publish()}else post{lastRead=DecodeResult.Rejected("MIFARE Classic is unavailable on this phone/tag")};return}
        try{
            tech.connect();val authed=tech.authenticateSectorWithKeyA(1,MifareClassic.KEY_DEFAULT);if(!authed)throw java.io.IOException("Sector 1 did not accept the documented default authentication")
            val current=tech.readBlock(tech.sectorToBlock(1));val currentRecords=if(current.all{it==0.toByte()})emptyList()else listOf(TagRecord(-1,codecId,current));val snapshot=TagSnapshot(tag.id,true,16,currentRecords)
            if(machine.state.phase in setOf(WritePhase.VERIFYING,WritePhase.VERIFICATION_PENDING,WritePhase.WRITE_OUTCOME_UNKNOWN)){reconcile(snapshot);post{lastRead=QidiBoxTagCodec.decode(current)};publish();return}
            if(!awaitingWrite()){post{lastRead=QidiBoxTagCodec.decode(current)};return}
            if(currentRecords.isNotEmpty()&&QidiBoxTagCodec.decode(current) is DecodeResult.Rejected){machine.rejectBeforeWrite("Existing MIFARE Classic data is not a recognized QIDI Box record and will not be overwritten");publish();return}
            val intent=machine.state.intent?:return;machine.inspected(snapshot,intent.payload.size);publish();if(machine.state.phase!=WritePhase.WRITING)return
            val uid=machine.state.uid?:return;if(!journal.save(uid,intent.payload,false,bindingContext,machine.state.tagNumber,null,codecId)){machine.writeFailedBeforeCall("Could not durably journal the QIDI write; no write was attempted");publish();return}
            try{tech.writeBlock(tech.sectorToBlock(1),intent.payload)}catch(e:Exception){machine.writeInterrupted("QIDI block write did not complete: ${e.javaClass.simpleName}");persistOrClear();publish();return}
            machine.writeReturned();persistOrClear();publish();val fresh=tech.readBlock(tech.sectorToBlock(1));reconcile(TagSnapshot(tag.id,true,16,listOf(TagRecord(-1,codecId,fresh))));post{lastRead=QidiBoxTagCodec.decode(fresh)};publish()
        }catch(e:Exception){handleTransportFailure(e,"MIFARE Classic")}finally{runCatching{tech.close()}}
    }
    private fun handleClassicDiscovery(tag:Tag){
        val tech=MifareClassic.get(tag)?:run{post{lastRead=DecodeResult.Rejected("MIFARE Classic is unavailable on this phone/tag")};return}
        try{
            requireClassic1k(tech)
            tech.connect()
            val cfsKey=CrealityCfsTagCodec.authenticationKey(tag.id)
            if(tech.authenticateSectorWithKeyA(1,cfsKey)){
                val current=readClassicSectorData(tech)
                post{lastRead=CrealityCfsTagCodec.decode(current)}
                return
            }
            reconnect(tech)
            if(!tech.authenticateSectorWithKeyA(1,MifareClassic.KEY_DEFAULT))throw java.io.IOException("Sector 1 did not accept a supported authentication method")
            val current=readClassicSectorData(tech)
            val block=current.copyOfRange(0,16)
            val qidi=QidiBoxTagCodec.decode(block)
            post{lastRead=when{qidi is DecodeResult.Supported->qidi;current.all{it==0.toByte()}->DecodeResult.Rejected("Blank MIFARE Classic 1K tag");else->DecodeResult.Rejected("MIFARE Classic data is not a supported QIDI Box or Creality CFS record")}}
        }catch(e:Exception){handleTransportFailure(e,"MIFARE Classic")}finally{runCatching{tech.close()}}
    }
    private fun handleCfs(tag:Tag,codecId:String){
        val tech=MifareClassic.get(tag)?:run{if(awaitingWrite()){machine.rejectBeforeWrite("Creality CFS requires a phone-supported MIFARE Classic 1K tag");publish()}else post{lastRead=DecodeResult.Rejected("MIFARE Classic is unavailable on this phone/tag")};return}
        try{
            requireClassic1k(tech)
            if(tag.id.size!=4)throw java.io.IOException("Creality CFS requires a four-byte MIFARE Classic UID")
            tech.connect()
            val cfsKey=CrealityCfsTagCodec.authenticationKey(tag.id)
            var derivedAuth=tech.authenticateSectorWithKeyA(1,cfsKey)
            var defaultAuth=false
            if(!derivedAuth){reconnect(tech);defaultAuth=tech.authenticateSectorWithKeyA(1,MifareClassic.KEY_DEFAULT)}
            if(!derivedAuth&&!defaultAuth)throw java.io.IOException("Sector 1 did not accept Creality CFS or blank-tag authentication")
            val current=readClassicSectorData(tech)
            val currentRecords=if(current.all{it==0.toByte()})emptyList()else listOf(TagRecord(-1,codecId,current))
            val snapshot=TagSnapshot(tag.id,true,48,currentRecords)
            if(machine.state.phase in setOf(WritePhase.VERIFYING,WritePhase.VERIFICATION_PENDING,WritePhase.WRITE_OUTCOME_UNKNOWN)){
                if(!derivedAuth){
                    val expected=journal.activePayload()
                    val trailer=if(defaultAuth)tech.readBlock(tech.sectorToBlock(1)+3)else null
                    val resumable=cfsTrailerVerificationRequired&&defaultAuth&&machine.state.uid?.contentEquals(tag.id)==true&&expected?.contentEquals(current)==true&&trailer!=null&&CrealityCfsTagCodec.hasSupportedBlankTrailer(trailer)
                    if(resumable&&cfsFinalizationResumeApproved){
                        cfsFinalizationResumeApproved=false;post{cfsFinalizationResumeAvailable=false}
                        try{cfsKey.copyInto(trailer,0);cfsKey.copyInto(trailer,10);tech.writeBlock(tech.sectorToBlock(1)+3,trailer)}catch(e:Exception){machine.reconciliationReadFailed("Approved CFS trailer completion did not finish: ${e.javaClass.simpleName}");persistOrClear();publish();return}
                        reconnect(tech);derivedAuth=tech.authenticateSectorWithKeyA(1,cfsKey)
                        if(derivedAuth&&validateCfsFinalization(tech,cfsKey)){val fresh=readClassicSectorData(tech);reconcile(TagSnapshot(tag.id,true,48,listOf(TagRecord(-1,codecId,fresh))));post{lastRead=CrealityCfsTagCodec.decode(fresh)};publish();return}
                    }
                    if(resumable)post{cfsFinalizationResumeAvailable=true}
                    val reason=if(resumable)"Encrypted data exactly matches the journal, but CFS trailer completion requires explicit approval" else "The tag still uses blank-tag authentication and is not CFS-ready"
                    if(machine.state.phase==WritePhase.VERIFYING)machine.verificationDeferred(reason)else machine.reconciliationReadFailed(reason);persistOrClear();publish();return
                }
                post{cfsFinalizationResumeAvailable=false}
                if(cfsTrailerVerificationRequired&&!validateCfsFinalization(tech,cfsKey)){if(machine.state.phase==WritePhase.VERIFYING)machine.verificationDeferred("The CFS sector trailer did not pass derived-key and access-byte verification")else machine.reconciliationReadFailed("The CFS sector trailer is not fully verified");persistOrClear();publish();return}
                val fresh=if(cfsTrailerVerificationRequired)readClassicSectorData(tech)else current
                reconcile(TagSnapshot(tag.id,true,48,listOf(TagRecord(-1,codecId,fresh))));post{lastRead=CrealityCfsTagCodec.decode(fresh)};publish();return
            }
            if(!awaitingWrite()){post{lastRead=if(derivedAuth)CrealityCfsTagCodec.decode(current)else DecodeResult.Rejected("Blank or non-CFS MIFARE Classic tag")};return}
            if(defaultAuth&&currentRecords.isNotEmpty()){machine.rejectBeforeWrite("Nonblank default-key MIFARE Classic data is not a finalized Creality CFS tag and will not be overwritten");publish();return}
            if(currentRecords.isNotEmpty()&&CrealityCfsTagCodec.decode(current) !is DecodeResult.Supported){machine.rejectBeforeWrite("Existing MIFARE Classic data is not a fully supported Creality CFS record and will not be overwritten");publish();return}
            val blankTrailer=if(defaultAuth){
                val trailer=tech.readBlock(tech.sectorToBlock(1)+3)
                if(!CrealityCfsTagCodec.hasSupportedBlankTrailer(trailer)){machine.rejectBeforeWrite("Blank tag sector trailer is not the documented CFS-compatible transport configuration; no write was attempted");publish();return}
                trailer
            }else null
            val intent=machine.state.intent?:return
            machine.inspected(snapshot,intent.payload.size);publish();if(machine.state.phase!=WritePhase.WRITING)return
            val uid=machine.state.uid?:return
            cfsTrailerVerificationRequired=blankTrailer!=null
            if(!journal.save(uid,intent.payload,false,bindingContext,machine.state.tagNumber,null,codecId,cfsTrailerVerificationRequired)){machine.writeFailedBeforeCall("Could not durably journal the Creality CFS write; no write was attempted");publish();return}
            try{
                intent.payload.toList().chunked(16).forEachIndexed{i,bytes->tech.writeBlock(tech.sectorToBlock(1)+i,bytes.toByteArray())}
                if(blankTrailer!=null){cfsKey.copyInto(blankTrailer,0);cfsKey.copyInto(blankTrailer,10);tech.writeBlock(tech.sectorToBlock(1)+3,blankTrailer)}
            }catch(e:Exception){machine.writeInterrupted("Creality CFS block write did not complete: ${e.javaClass.simpleName}");persistOrClear();publish();return}
            machine.writeReturned();persistOrClear();publish()
            reconnect(tech)
            derivedAuth=tech.authenticateSectorWithKeyA(1,cfsKey)
            if(!derivedAuth){machine.verificationDeferred("The write call completed, but CFS authentication could not be verified");persistOrClear();publish();return}
            if(cfsTrailerVerificationRequired&&!validateCfsFinalization(tech,cfsKey)){machine.verificationDeferred("The write call completed, but the CFS keys and access bytes could not be verified");persistOrClear();publish();return}
            val fresh=readClassicSectorData(tech)
            reconcile(TagSnapshot(tag.id,true,48,listOf(TagRecord(-1,codecId,fresh))));post{lastRead=CrealityCfsTagCodec.decode(fresh)};publish()
        }catch(e:Exception){handleTransportFailure(e,"Creality CFS")}finally{runCatching{tech.close()}}
    }
    private fun requireClassic1k(tech:MifareClassic){
        if(tech.type!=MifareClassic.TYPE_CLASSIC||tech.size<MifareClassic.SIZE_1K)throw java.io.IOException("A MIFARE Classic 1K-compatible tag is required")
    }
    private fun reconnect(tech:MifareClassic){runCatching{tech.close()};tech.connect()}
    private fun readClassicSectorData(tech:MifareClassic):ByteArray {val first=tech.sectorToBlock(1);return ByteArray(48).also{out->repeat(3){i->tech.readBlock(first+i).copyInto(out,i*16)}}}
    private fun validateCfsFinalization(tech:MifareClassic,key:ByteArray):Boolean {
        val trailer=tech.readBlock(tech.sectorToBlock(1)+3)
        return CrealityCfsTagCodec.hasFinalizedTrailer(trailer,key)
    }
    private fun handleTransportFailure(error:Exception,label:String){if(machine.state.phase==WritePhase.WRITING)machine.writeInterrupted("$label connection lost during write")else if(machine.state.phase==WritePhase.VERIFYING)machine.verificationDeferred("$label verification read failed")else if(machine.state.phase in setOf(WritePhase.VERIFICATION_PENDING,WritePhase.WRITE_OUTCOME_UNKNOWN))machine.reconciliationReadFailed("$label reconciliation failed: ${error.javaClass.simpleName}")else if(awaitingWrite())machine.rejectBeforeWrite("$label read failed: ${error.message?:error.javaClass.simpleName}")else post{lastRead=DecodeResult.Rejected("$label read failed: ${error.javaClass.simpleName}")};persistOrClear();publish()}
    private fun readNtagUserPages(tech:MifareUltralight,lastUserPage:Int=39):ByteArray{require(lastUserPage>=4);val out=ByteArray((lastUserPage-3)*4);for(page in 4..lastUserPage step 4){val pageCount=minOf(4,lastUserPage-page+1);val byteCount=pageCount*4;val bytes=tech.readPages(page);if(bytes.size<byteCount)throw java.io.IOException("Short NTAG read at page $page");bytes.copyOfRange(0,byteCount).copyInto(out,(page-4)*4)};return out}
    private fun decodeRawNtag(raw:ByteArray):DecodeResult {
        val canvas=raw.takeIf{it.size>=ELEGOO_USER_OFFSET+ElegooCanvasTagCodec.PAYLOAD_BYTES}?.copyOfRange(ELEGOO_USER_OFFSET,ELEGOO_USER_OFFSET+ElegooCanvasTagCodec.PAYLOAD_BYTES)
        val candidates=listOfNotNull(canvas?.let(ElegooCanvasTagCodec::decode),AnycubicAceTagCodec.decode(raw),TigerTagWriteCodec.decode(raw))
        return candidates.firstOrNull{it is DecodeResult.Supported||it is DecodeResult.ReadOnly}?:DecodeResult.Rejected("Raw NTAG data is not a supported ELEGOO CANVAS, Anycubic ACE, or TigerTag format")
    }
    // Metadata only: never retain unknown tag payloads or use discovery cache for verification.
    private fun recordRead(tag: Tag, ndef: Ndef, fresh: NdefMessage?, stage: String) {
        val report = "${System.currentTimeMillis()} · $stage\nUID ${tag.id.joinToString("") { "%02X".format(it) }} · ${ndef.maxSize} bytes · writable=${ndef.isWritable}\n${tag.techList.joinToString { it.substringAfterLast('.') }} · ${ndef.type}\nDiscovery: ${messageSummary(ndef.cachedNdefMessage)}\nActive RF: ${messageSummary(fresh)}"
        // Diagnostic persistence cannot change a write outcome or interrupt RF handling.
        val saved = runCatching {
            val previous = diagnostics.getString("history", "").orEmpty().split("\n---\n").filter { it.isNotEmpty() }
            diagnostics.edit().putString("history", (previous.takeLast(19) + report).joinToString("\n---\n")).commit()
        }.getOrDefault(false)
        post { readDiagnostic = report + if (saved) "" else "\nDiagnostic history could not be saved" }
    }
    internal fun messageSummary(message: NdefMessage?): String {
        if (message == null) return "no message (null)"
        val bytes = message.toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return "${message.records.size} record(s), ${bytes.size} bytes, SHA256 $digest"
    }
    internal fun decodeSingleTag(message:NdefMessage?):DecodeResult {
        val records=message?.records?:emptyArray()
        if(records.isEmpty())return DecodeResult.Rejected("No NDEF message returned by this read. This does not establish whether an earlier write persisted.")
        val supported = records.mapNotNull { record ->
            if(record.tnf!=NdefRecord.TNF_MIME_MEDIA) null else {
                val mime=record.type.toString(Charsets.US_ASCII).lowercase()
                when(mime) { OpenSpoolCodec.MIME -> OpenSpoolCodec.decode(record.payload); else -> ExternalTagRegistry.decode(mime,record.payload) }
            }
        }
        if(supported.isEmpty())return DecodeResult.Rejected("No supported filament MIME record found")
        if(supported.size>1)return DecodeResult.Rejected("Multiple NDEF records found; filament conversion is ambiguous")
        return supported.single()
    }
    internal fun decodeSingleOpenSpool(message:NdefMessage?):DecodeResult = decodeSingleTag(message)
}

internal data class BindingFinalization(val persistence: BindingPersistence, val errorMessage: String?=null)
internal fun finalizeVerifiedBinding(write: VerifiedTagWrite?, callback: ((VerifiedTagWrite)->Unit)?, journal: WriteJournal): BindingFinalization {
    if(write==null) { journal.clear(); return BindingFinalization(BindingPersistence.NOT_REQUIRED) }
    return runCatching { requireNotNull(callback) { "No binding persistence callback is available" };callback(write) }
        .fold(
            onSuccess={journal.clear();BindingFinalization(BindingPersistence.SAVED)},
            onFailure={error->journal.save(write.uid,write.payload,true,write.context,write.bindingOrder,journal.activeMessage());BindingFinalization(BindingPersistence.RETRY_REQUIRED,error.message?:"unknown error")},
        )
}

internal fun readFreshNdefWithRetries(
    attempts: Int = 6,
    pauseMillis: Long = 180,
    pause: (Long) -> Unit = Thread::sleep,
    read: () -> NdefMessage?,
): NdefMessage? {
    require(attempts > 0)
    repeat(attempts) { attempt ->
        read()?.let { return it }
        if (attempt + 1 < attempts) pause(pauseMillis)
    }
    return null
}

internal class WriteJournal(context:Context){
    data class Entry(val uid:ByteArray,val payload:ByteArray,val verificationPending:Boolean=false,val bindingContext:WriteBindingContext?=null,val tagNumber:Int=1,val expectedMessage:ByteArray?=null,val codecId:String?=null,val cfsTrailerVerificationRequired:Boolean=false)
    private val prefs=context.getSharedPreferences("nfc-write-journal",Context.MODE_PRIVATE)
    fun save(uid:ByteArray,payload:ByteArray,verificationPending:Boolean=false,bindingContext:WriteBindingContext?=null,tagNumber:Int=1,expectedMessage:ByteArray?=null,codecId:String?=bindingContext?.codecId,cfsTrailerVerificationRequired:Boolean=false):Boolean {
        require(tagNumber in 1..2)
        val edit=prefs.edit().putInt("version",5).putString("uid",Base64.encodeToString(uid,Base64.NO_WRAP)).putString("payload",Base64.encodeToString(payload,Base64.NO_WRAP)).putBoolean("verification_pending",verificationPending).putInt("tag_number",tagNumber).putBoolean("cfs_trailer_verification_required",cfsTrailerVerificationRequired)
        if(expectedMessage==null)edit.remove("expected_message") else edit.putString("expected_message",Base64.encodeToString(expectedMessage,Base64.NO_WRAP))
        if(codecId==null)edit.remove("intent_codec_id") else edit.putString("intent_codec_id",codecId)
        if(bindingContext==null)edit.remove("spool_id").remove("codec_id") else edit.putString("spool_id",bindingContext.spoolId).putString("codec_id",bindingContext.codecId)
        return edit.commit()
    }
    fun load():Entry?=runCatching{val version=prefs.getInt("version",0);if(version !in 1..5)return null;val uid=Base64.decode(prefs.getString("uid",null)?:return null,Base64.DEFAULT);val payload=Base64.decode(prefs.getString("payload",null)?:return null,Base64.DEFAULT);if(uid.isEmpty()||payload.isEmpty())return null;val spool=if(version>=3)prefs.getString("spool_id",null)else null;val bindingCodec=if(version>=3)prefs.getString("codec_id",null)else null;val binding=if(!spool.isNullOrBlank()&&!bindingCodec.isNullOrBlank())WriteBindingContext(spool,bindingCodec)else null;val message=if(version>=4)prefs.getString("expected_message",null)?.let{Base64.decode(it,Base64.DEFAULT)}else null;val intentCodec=if(version>=4)prefs.getString("intent_codec_id",null)else bindingCodec;Entry(uid,payload,version>=2&&prefs.getBoolean("verification_pending",false),binding,if(version>=3)prefs.getInt("tag_number",1)else 1,message,intentCodec,version>=5&&prefs.getBoolean("cfs_trailer_verification_required",false))}.getOrNull()
    fun activePayload():ByteArray?=load()?.payload
    fun activeMessage():ByteArray?=load()?.expectedMessage
    data class ArchivedEntry(val timestamp: Long, val uid: ByteArray, val payload: ByteArray)
    fun archives(): List<ArchivedEntry> = prefs.all.entries.filter { it.key.startsWith("archive_") && it.key != "archive_count" }.mapNotNull { (_, value) ->
        runCatching { val parts = (value as String).split(':', limit = 3); ArchivedEntry(parts[0].toLong(), Base64.decode(parts[1], Base64.DEFAULT), Base64.decode(parts[2], Base64.DEFAULT)) }.getOrNull()
    }.sortedByDescending { it.timestamp }
    fun archiveCount():Int=prefs.getInt("archive_count",0)
    fun archive(uid:ByteArray,payload:ByteArray):Boolean{val count=prefs.getInt("archive_count",0);check(count < Int.MAX_VALUE){"Unresolved archive capacity reached"};val slot=count;return prefs.edit().putInt("archive_count",count+1).putString("archive_$slot",System.currentTimeMillis().toString()+":"+Base64.encodeToString(uid,Base64.NO_WRAP)+":"+Base64.encodeToString(payload,Base64.NO_WRAP)).remove("version").remove("uid").remove("payload").remove("verification_pending").remove("spool_id").remove("codec_id").remove("tag_number").remove("expected_message").remove("intent_codec_id").remove("cfs_trailer_verification_required").commit()}
    fun clear():Boolean=prefs.edit().remove("version").remove("uid").remove("payload").remove("verification_pending").remove("spool_id").remove("codec_id").remove("tag_number").remove("expected_message").remove("intent_codec_id").remove("cfs_trailer_verification_required").commit()
}

internal fun messageForIntent(intent:OpenSpoolIntent)=NdefMessage(intent.ndefRecords.map { record ->
    NdefRecord(record.tnf.toShort(),record.mime.encodeToByteArray(),record.id,record.payload)
}.toTypedArray())

private fun recordsFromMessageBytes(bytes:ByteArray):List<TagRecord> = NdefMessage(bytes).records.map {
    TagRecord(it.tnf.toInt(),it.type.toString(Charsets.US_ASCII),it.payload,it.id)
}

private fun DecodeResult?.warningsOrEmpty()=(this as? DecodeResult.Supported)?.warnings.orEmpty()
