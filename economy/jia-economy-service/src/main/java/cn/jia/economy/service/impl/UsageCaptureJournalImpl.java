package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.EconomyUsageCaptureMapper;
import cn.jia.economy.usage.*;
import cn.jia.economy.service.EconomyScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** The authentication transaction owns sample -> capture -> evidence/receipt lock order.
 * Return conflict as data so the quarantine commits before the controller returns HTTP 409. */
@Service
@Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
public class UsageCaptureJournalImpl implements UsageCaptureJournal {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final EconomyUsageCaptureMapper db;
    public UsageCaptureJournalImpl(EconomyUsageCaptureMapper db){this.db=db;}
    private static EconomyUsageCaptureMapper.Binding key(Binding b){return new EconomyUsageCaptureMapper.Binding(b.tenantId(),b.clientId(),b.executionId(),b.runId(),b.captureId(),b.ownerJiacn(),b.agentId(),b.taskId());}
    private static boolean matches(Binding b,EconomyUsageCaptureMapper.Capture c){return c!=null&&Objects.equals(b.ownerJiacn(),c.ownerJiacn())&&Objects.equals(b.agentId(),c.agentId())&&Objects.equals(b.taskId(),c.taskId());}
    private static Result status(State s){return new Result(s,null);}
    private static Result stored(EconomyUsageCaptureMapper.Receipt r){return r==null?status(State.NOT_FOUND):"CAS_CONFLICT".equals(r.processingState())?status(State.CONFLICT):r.ackJson()==null?status(State.PENDING):new Result(State.DURABLE,r.ackJson());}
    private static String key(BigInteger revision){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(('"'+revision.toString()+'"').getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private void change(long pk,List<String> members,String reason){if(db.invalidateCapture(pk,reason)!=1)throw new IllegalStateException("Capture disappeared");for(String id:members)if(db.invalidateSample(id)!=1)throw new IllegalStateException("Membership disappeared");}
    private Result conflict(long pk,List<String> members,String reason){change(pk,members,reason);return status(State.CONFLICT);}
    public Result readReceipt(Binding b,String receiptId){var c=db.lockCapture(key(b));if(!matches(b,c))return status(State.NOT_FOUND);return c.conflictReason()!=null?status(State.CONFLICT):stored(db.receipt(c.id(),receiptId));}
    private void publishHead(long pk,String id,String revisionKey,String attempt){
        db.clearHead(pk,id);if(db.publishHead(pk,id,revisionKey,attempt)!=1)throw new IllegalStateException("Missing associated revision");
    }
    public Result readRecord(Binding b,String id){
        var c=db.lockCapture(key(b));if(!matches(b,c))return status(State.NOT_FOUND);if(c.conflictReason()!=null)return status(State.CONFLICT);
        var head=db.head(c.id(),id);if(head==null)return status(db.recordBinding(c.id(),id)==null?State.NOT_FOUND:State.PENDING);
        var view=new LinkedHashMap<String,Object>();view.put("recordId",id);view.put("sourceRevision",head.sourceRevision());view.put("evidenceSha256",head.evidenceSha256());
        view.put("ledgerRevision",head.ledgerAttemptId()==null?null:Objects.requireNonNull(db.ledgerRevision(b.tenantId(),b.clientId(),head.ledgerAttemptId())).toString());
        return new Result(State.DURABLE,JSON.writeValueAsString(view));
    }
    private LinkedEvidence dependency(long pk,String id){
        var first=db.evidence(pk,id,key(BigInteger.ONE));return first==null?null:new LinkedEvidence(db.head(pk,id)!=null,first.evidenceJson());
    }
    public Result recover(String tenant,String client,long pk,String receipt,MetadataLinks links,ResponseOrigin origin){
        var r=db.recovery(tenant,client,pk,receipt);if(r==null)return status(State.NOT_FOUND);
        var b=new Binding(r.tenantId(),r.clientId(),r.ownerJiacn(),r.agentId(),r.taskId(),r.executionId(),r.runId(),r.captureId());
        return switch(r.kind()){case "SOURCE","PLAN","REQUEST","SOURCE_FINAL","CAPTURE_FINAL" -> linkMetadata(b,receipt,links);case "RESPONSE" -> bindResponse(b,receipt,origin);default -> status(State.NOT_FOUND);};
    }
    private static Result waiting(Result r,long pk){return new Result(r.state(),r.ackJson(),r.work(),pk);}
    public Result linkMetadata(Binding b,String receiptId,MetadataLinks adapter){
        var binding=key(b);var members=b.taskId()==null?List.<String>of():db.lockMembers(binding);
        var c=db.lockCapture(binding);if(!matches(b,c))return status(State.NOT_FOUND);if(c.conflictReason()!=null)return status(State.CONFLICT);
        long pk=c.id();var r=db.receipt(pk,receiptId);var result=stored(r);if(result.state()!=State.DURABLE)return result;
        var e=db.evidence(pk,r.recordId(),r.revisionKey());if("RESPONSE".equals(e.kind()))throw new IllegalStateException("Response requires settlement");
        var current=db.head(pk,r.recordId());var n=new BigInteger(e.sourceRevision());
        var head=current==null?BigInteger.ZERO:new BigInteger(current.sourceRevision());
        if(n.compareTo(head)<=0)return result;
        if(!head.add(BigInteger.ONE).equals(n))return waiting(result,pk);
        var relation=adapter.resolve(e.evidenceJson(),head.signum()==0?null:db.evidence(pk,e.recordId(),key(head)).evidenceJson(),id->dependency(pk,id));
        if(relation==Link.CONFLICT)return conflict(pk,members,"METADATA_LINEAGE");
        if(relation==Link.ASSOCIATED){publishHead(pk,e.recordId(),r.revisionKey(),null);change(pk,members,null);}
        return relation==Link.WAITING?waiting(result,pk):result; // durable receipt does not assert association, cost or coverage
    }
    /** Source ownership is decided before the separate ledger transaction, never by ledger owner/task alone. */
    public Result bindResponse(Binding b,String receiptId,ResponseOrigin adapter){
        var binding=key(b);var members=b.taskId()==null?List.<String>of():db.lockMembers(binding);
        var c=db.lockCapture(binding);if(!matches(b,c))return status(State.NOT_FOUND);
        long pk=c.id();if(c.conflictReason()!=null)return status(State.CONFLICT);
        var receipt=db.receipt(pk,receiptId);var result=stored(receipt);if(result.state()!=State.PENDING)return result;
        result=waiting(result,pk);
        var response=db.evidence(pk,receipt.recordId(),receipt.revisionKey());
        if(response==null||!"RESPONSE".equals(response.kind()))throw new IllegalStateException("Missing pending response");
        // A future revision may arrive first; do not let a gap reserve a provider identity.
        String previous=null;
        if(!"1".equals(response.sourceRevision())){
            var prev=db.evidence(pk,response.recordId(),key(new BigInteger(response.sourceRevision()).subtract(BigInteger.ONE)));
            if(prev==null||!"APPLIED".equals(prev.associationState()))return result;
            previous=prev.evidenceJson();
        }
        var origin=adapter.resolve(response.evidenceJson(),previous,id->{var dep=dependency(pk,id);return dep==null||!dep.associated()?null:dep.canonical();});
        if(origin.conflict())return conflict(pk,members,"RESPONSE_ORIGIN");
        if(origin.attemptId()==null)return result;
        var claim=new EconomyUsageCaptureMapper.Origin(b.tenantId(),b.clientId(),origin.attemptId(),pk,response.recordId());
        db.ensureOrigin(claim);
        if(!claim.equals(db.lockOrigin(claim)))return conflict(pk,members,"RESPONSE_OWNER");
        String owner=db.applying(pk,response.recordId(),receipt.revisionKey());
        if(owner!=null&&!owner.equals(receiptId)){
            var original=db.receipt(pk,owner);
            if(original.ackJson()==null)return result; // original submission must finish/recover first
            if(!original.expectedRevision().equals(receipt.expectedRevision()))return rejectCas(pk,receiptId);
            return ack(b,pk,receipt,response,JSON.readTree(original.ackJson()).get("ledger"));
        }
        long expected;
        try{expected=new BigInteger(receipt.expectedRevision()).longValueExact();}catch(ArithmeticException overflow){return rejectCas(pk,receiptId);}
        if(owner==null)db.application(pk,response.recordId(),receipt.revisionKey(),receiptId,"APPLYING");
        var costBinding=new UsageCostService.Binding(new EconomyScope(b.tenantId(),b.clientId()),b.ownerJiacn(),b.taskId(),"NATIVE_RESPONSE_USAGE",origin.attemptId());
        // Runtime's current HTTP adapter has no attested actual billing identity or price. Retain all measured evidence independently.
        var observation=new UsageCostService.Observation(costBinding,receiptId,expected,response.evidenceJson(),null,null,origin.observedAt());
        return new Result(State.PENDING,null,new Work(b,receipt.requestSha256(),observation,origin.facts()),pk);
    }
    private Result rejectCas(long pk,String receipt){if(db.rejectCas(pk,receipt)!=1)throw new IllegalStateException("CAS state changed");return status(State.CONFLICT);}
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Exception.class)
    public Result finish(Work work,UsageCostService.Receipt applied,UsageCostConflict.Reason rejected){
        Binding b=work.binding();var binding=key(b);var members=b.taskId()==null?List.<String>of():db.lockMembers(binding);
        var c=db.lockCapture(binding);if(!matches(b,c))return status(State.NOT_FOUND);if(c.conflictReason()!=null)return status(State.CONFLICT);
        long pk=c.id();String id=work.observation().receiptId();var r=db.receipt(pk,id);var result=stored(r);if(result.state()!=State.PENDING)return result;
        if(!r.requestSha256().equals(work.requestHash())||!id.equals(db.applying(pk,r.recordId(),r.revisionKey())))throw new IllegalStateException("Lost application binding");
        if(rejected!=null){
            if(rejected!=UsageCostConflict.Reason.REVISION)return conflict(pk,members,"LEDGER_"+rejected.name());
            db.application(pk,r.recordId(),r.revisionKey(),null,"WAITING");return rejectCas(pk,id);
        }
        var ledger=new LinkedHashMap<String,Object>();ledger.put("attemptId",applied.attemptId());ledger.put("revision",Long.toString(applied.revision()));ledger.put("status",applied.status().name());ledger.put("costYuan",applied.costYuan()==null?null:applied.costYuan().toPlainString());
        db.application(pk,r.recordId(),r.revisionKey(),id,"APPLIED");publishHead(pk,r.recordId(),r.revisionKey(),applied.attemptId());change(pk,members,null);
        return ack(b,pk,r,db.evidence(pk,r.recordId(),r.revisionKey()),ledger);
    }
    public Result register(Binding b,Submission s){
        var binding=key(b);var members=b.taskId()==null?List.<String>of():db.lockMembers(binding);
        db.ensureCapture(binding);var c=db.lockCapture(binding);if(!matches(b,c))return status(State.NOT_FOUND);
        long pk=c.id();if(c.conflictReason()!=null)return status(State.CONFLICT);
        var old=db.receipt(pk,s.receiptId());if(old!=null)return old.requestSha256().equals(s.requestSha256())?stored(old):conflict(pk,members,"RECEIPT_CONTENT");
        var n=new BigInteger(s.sourceRevision());String k=key(n);var record=db.evidence(pk,s.recordId(),k);
        if(record!=null && (!record.sourceRevision().equals(s.sourceRevision())||!record.evidenceSha256().equals(s.evidenceSha256())||!record.evidenceJson().equals(s.canonicalEvidence())))return conflict(pk,members,"RECORD_CONTENT");
        if(record==null){
            String bindingSha=db.recordBinding(pk,s.recordId());
            if(bindingSha!=null&&!bindingSha.equals(s.bindingSha256()))return conflict(pk,members,"RECORD_BINDING");
            var prev=n.equals(BigInteger.ONE)?null:db.evidence(pk,s.recordId(),key(n.subtract(BigInteger.ONE)));
            var next=db.evidence(pk,s.recordId(),key(n.add(BigInteger.ONE)));
            if(prev!=null&&!prev.evidenceSha256().equals(s.previousEvidenceSha256())||next!=null&&!s.evidenceSha256().equals(next.previousEvidenceSha256()))return conflict(pk,members,"REVISION_LINK");
            db.appendEvidence(new EconomyUsageCaptureMapper.Evidence(pk,s.recordId(),k,s.sourceRevision(),s.kind(),s.evidenceSha256(),s.previousEvidenceSha256(),s.canonicalEvidence(),s.bindingSha256(),"WAITING"));
            change(pk,members,null);
        }
        db.appendSubmission(new EconomyUsageCaptureMapper.Receipt(pk,s.receiptId(),s.recordId(),k,s.requestId(),s.requestSha256(),s.expectedRevision(),"PENDING",null));
        if("RESPONSE".equals(s.kind()))return status(State.PENDING); // never manufacture a ledger receipt
        return ack(b,pk,s.receiptId(),s.requestSha256(),s.recordId(),s.sourceRevision(),s.evidenceSha256(),null);
    }
    private Result ack(Binding b,long pk,EconomyUsageCaptureMapper.Receipt receipt,EconomyUsageCaptureMapper.Evidence e,Object ledger){
        return ack(b,pk,receipt.receiptId(),receipt.requestSha256(),e.recordId(),e.sourceRevision(),e.evidenceSha256(),ledger);
    }
    private Result ack(Binding b,long pk,String receiptId,String requestHash,String recordId,String sourceRevision,String evidenceSha,Object ledger){
        var ack=new LinkedHashMap<String,Object>();ack.put("schemaVersion",2);ack.put("executionId",b.executionId());ack.put("runId",b.runId());ack.put("captureId",b.captureId());ack.put("receiptId",receiptId);ack.put("recordId",recordId);ack.put("sourceRevision",sourceRevision);ack.put("evidenceSha256",evidenceSha);ack.put("acceptedAt",System.currentTimeMillis());ack.put("acceptance","DURABLE");ack.put("ledger",ledger);
        String raw=JSON.writeValueAsString(ack);if(db.finishReceipt(pk,receiptId,requestHash,raw)!=1)throw new IllegalStateException("Receipt transition lost");return new Result(State.DURABLE,raw);
    }
}
