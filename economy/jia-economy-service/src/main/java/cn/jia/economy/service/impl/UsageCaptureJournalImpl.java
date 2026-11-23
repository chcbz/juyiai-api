package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.EconomyUsageCaptureMapper;
import cn.jia.economy.usage.UsageCaptureJournal;
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
    private static Result stored(EconomyUsageCaptureMapper.Receipt r){return r==null?status(State.NOT_FOUND):r.ackJson()==null?status(State.PENDING):new Result(State.DURABLE,r.ackJson());}
    private static String key(BigInteger revision){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(('"'+revision.toString()+'"').getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private void change(long pk,List<String> members,String reason){if(db.invalidateCapture(pk,reason)!=1)throw new IllegalStateException("Capture disappeared");for(String id:members)if(db.invalidateSample(id)!=1)throw new IllegalStateException("Membership disappeared");}
    private Result conflict(long pk,List<String> members,String reason){change(pk,members,reason);return status(State.CONFLICT);}
    public Result readReceipt(Binding b,String receiptId){var c=db.lockCapture(key(b));if(!matches(b,c))return status(State.NOT_FOUND);return c.conflictReason()!=null?status(State.CONFLICT):stored(db.receipt(c.id(),receiptId));}
    /** Source ownership is decided before the separate ledger transaction, never by ledger owner/task alone. */
    public Result bindResponse(Binding b,String receiptId,ResponseOrigin adapter){
        var binding=key(b);var members=b.taskId()==null?List.<String>of():db.lockMembers(binding);
        var c=db.lockCapture(binding);if(!matches(b,c))return status(State.NOT_FOUND);
        long pk=c.id();if(c.conflictReason()!=null)return status(State.CONFLICT);
        var receipt=db.receipt(pk,receiptId);var result=stored(receipt);if(result.state()!=State.PENDING)return result;
        var response=db.evidence(pk,receipt.recordId(),receipt.revisionKey());
        if(response==null||!"RESPONSE".equals(response.kind()))throw new IllegalStateException("Missing pending response");
        // A future revision may arrive first; do not let a gap reserve a provider identity.
        if(!"1".equals(response.sourceRevision()))return result;
        var origin=adapter.resolve(response.evidenceJson(),id->{var row=db.evidence(pk,id,key(BigInteger.ONE));return row==null?null:row.evidenceJson();});
        if(origin.conflict())return conflict(pk,members,"RESPONSE_ORIGIN");
        if(origin.attemptId()==null)return result;
        var claim=new EconomyUsageCaptureMapper.Origin(b.tenantId(),b.clientId(),origin.attemptId(),pk,response.recordId());
        db.ensureOrigin(claim);
        if(!claim.equals(db.lockOrigin(claim)))return conflict(pk,members,"RESPONSE_OWNER");
        // Origin is durable, not proof of predecessor/ledger/coverage. No ACK without ledger result.
        return result;
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
        var ack=new LinkedHashMap<String,Object>();ack.put("schemaVersion",2);ack.put("executionId",b.executionId());ack.put("runId",b.runId());ack.put("captureId",b.captureId());ack.put("receiptId",s.receiptId());ack.put("recordId",s.recordId());ack.put("sourceRevision",s.sourceRevision());ack.put("evidenceSha256",s.evidenceSha256());ack.put("acceptedAt",System.currentTimeMillis());ack.put("acceptance","DURABLE");ack.put("ledger",null);
        String raw=JSON.writeValueAsString(ack);if(db.finishReceipt(pk,s.receiptId(),s.requestSha256(),raw)!=1)throw new IllegalStateException("Receipt transition lost");return new Result(State.DURABLE,raw);
    }
}
