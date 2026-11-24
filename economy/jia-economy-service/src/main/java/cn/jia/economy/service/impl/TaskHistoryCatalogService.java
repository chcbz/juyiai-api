package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.ConfirmedSampleMapper;
import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.task.TaskHistoryCatalog;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskHistoryCatalogService implements TaskHistoryCatalog,cn.jia.economy.task.TaskCalibrationObservations {
    private final ConfirmedSampleMapper db;
    private final ObjectMapper json;
    private final boolean benchmarks;
    public TaskHistoryCatalogService(ConfirmedSampleMapper db,ObjectMapper json){this(db,json,false);}
    @org.springframework.beans.factory.annotation.Autowired
    public TaskHistoryCatalogService(ConfirmedSampleMapper db,ObjectMapper json,
            @org.springframework.beans.factory.annotation.Value("${jia.economy.measured-benchmark-use:false}") boolean benchmarks){this.db=db;this.json=json;this.benchmarks=benchmarks;}
    @Transactional(propagation=Propagation.NEVER)
    public List<Entry> candidatePage(Task query,Group group,cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,String after){
        if(!benchmarks)return page(query,group,after);
        if(!group.digest().equals(pin.group()))throw new IllegalArgumentException("Wrong benchmark scope");
        var h=db.history(query,pin.group(),after,PAGE);var b=db.benchmarkPage(query,pin,after,PAGE);
        // Both sources share the sample table's global primary key. Merge bounded keysets before vector decoding.
        var result=new java.util.ArrayList<Entry>();int i=0,j=0;
        while(result.size()<PAGE && (i<h.size() || j<b.size())){
            boolean takeHistory=j==b.size() || (i<h.size() && h.get(i).id().compareTo(b.get(j).id())<0);
            result.add(entry(group,takeHistory?h.get(i++):b.get(j++)));
        }return result;
    }
    @Transactional(propagation=Propagation.NEVER)
    public List<cn.jia.economy.pricing.MeasuredTaskRepricing.Fact> candidateQuantities(Task q,Group g,cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,Revision r){
        var facts=quantities(q,g,r);if(facts!=null||!benchmarks)return facts;
        if(!g.digest().equals(pin.group()))throw new IllegalArgumentException("Wrong benchmark scope");
        var raw=db.benchmarkQuantities(q,pin,r.sampleId(),r.revision());return raw==null?null:operations(raw);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean lockCandidate(Task q,Group g,cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,Revision r){
        if(lockCurrent(q,g,r))return true;
        return benchmarks && g.digest().equals(pin.group()) && db.lockGrant(q,pin,r.sampleId())!=null && db.lockBenchmark(q,pin,r.sampleId(),r.revision())!=null;
    }
    @Transactional(propagation=Propagation.NEVER)
    public List<Entry> page(Task query,Group group,String after){
        return db.history(query,group.digest(),after,PAGE).stream().map(r->entry(group,r)).toList();
    }
    private Entry entry(Group group,ConfirmedSampleMapper.HistoryRow r){
            var features=json.readValue(r.featuresJson(),TaskEstimateFeatures.class);
            if(!features.digest().equals(r.featureSha())||!Group.of(group.execution(),features).equals(group))
                throw new IllegalStateException("History feature commitment mismatch");
            return new Entry(new Revision(r.id(),r.revision()),r.taskId(),features);
    }
    @Transactional(propagation=Propagation.NEVER)
    public List<cn.jia.economy.pricing.MeasuredTaskRepricing.Fact> quantities(Task query,Group group,Revision selected){
        var payload=db.historyQuantities(query,group.digest(),selected.sampleId(),selected.revision());if(payload==null)return null;
        return operations(payload);
    }
    private List<cn.jia.economy.pricing.MeasuredTaskRepricing.Fact> operations(String payload){
        var rows=json.readValue(payload,ProjectedTaskOperation[].class);
        if(rows.length==0)throw new IllegalStateException("Empty READY operation projection");
        return java.util.Arrays.stream(rows).map(r->r.fact(json)).toList();
    }
    private record Snapshot(String schema,cn.jia.economy.pricing.TaskEstimateCalibration.PrivateGroup ownerGroup,String taskId,
            Revision source,cn.jia.economy.pricing.TaskEstimateEvaluation.Case roster,String requirementSha,String inputSha,
            String featureSha,String features,String execution,String operations){@Override public String toString(){return "Snapshot[private]";}}
    private static String hash(String text){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    @Override @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Frozen freezeConfirmed(Task original,Group group,Revision selected,String family,cn.jia.economy.pricing.TaskEstimateEvaluation.Split partition){
        var row=db.selectedForCalibration(original,group.digest(),selected.sampleId(),selected.revision());
        if(row==null)throw new NotEligible();
        var roster=new cn.jia.economy.pricing.TaskEstimateEvaluation.Case(original.taskId(),family,row.featuresAt(),db.observedByMillis(),partition);
        var snapshot=new Snapshot("confirmed-observation-v1",group.privateCalibrationGroup(original),original.taskId(),selected,roster,
                row.requirementSha(),row.inputSha(),row.featureSha(),row.features(),row.execution(),row.operations());
        sample(group,snapshot); // Do not return a capsule that failed projection checks.
        String bytes=json.writeValueAsString(snapshot);return new Frozen(hash(bytes),bytes);
    }
    @Override public cn.jia.economy.pricing.TaskEstimateCalibration.Sample replay(Task original,Group group,Revision selected,String family,
            cn.jia.economy.pricing.TaskEstimateEvaluation.Split partition,String expectedSha256,String bytes){
        if(!hash(bytes).equals(expectedSha256))throw new IllegalArgumentException("Changed observation bytes");
        var snapshot=json.readValue(bytes,Snapshot.class);
        if(!"confirmed-observation-v1".equals(snapshot.schema())||!group.privateCalibrationGroup(original).equals(snapshot.ownerGroup())
                ||!original.taskId().equals(snapshot.taskId())||!original.taskId().equals(snapshot.roster().task())||!selected.equals(snapshot.source())
                ||!family.equals(snapshot.roster().family())||partition!=snapshot.roster().split())throw new IllegalArgumentException("Wrong frozen roster entry");
        return sample(group,snapshot);
    }
    private cn.jia.economy.pricing.TaskEstimateCalibration.Sample sample(Group group,Snapshot row){
        var features=json.readValue(row.features(),TaskEstimateFeatures.class);
        var actual=json.readValue(row.execution(),cn.jia.economy.task.TaskBillingService.MeasuredGroup.class);
        if(!group.equals(Group.of(actual,features))||!features.digest().equals(row.featureSha())
                ||!features.requirementSha256().equals(row.requirementSha())||!features.inputSnapshotSha256().equals(row.inputSha()))
            throw new IllegalStateException("Calibration source commitment mismatch");
        return new cn.jia.economy.pricing.TaskEstimateCalibration.Sample(row.ownerGroup(),row.source().sampleId(),row.source().revision(),row.roster(),features,operations(row.operations()));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean lockCurrent(Task query,Group group,Revision selected){
        return db.lockHistory(query,group.digest(),selected.sampleId(),selected.revision())!=null;
    }
}
