package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.ConfirmedSampleMapper;
import cn.jia.economy.pricing.TaskEstimateFeatures;
import cn.jia.economy.task.TaskBillingService.Task;
import cn.jia.economy.task.TaskHistoryCatalog;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
    private final boolean compact;
    public TaskHistoryCatalogService(ConfirmedSampleMapper db,ObjectMapper json,boolean benchmarks){this(db,json,benchmarks,false);}
    private final ObservationRegistry observations;
    public TaskHistoryCatalogService(ConfirmedSampleMapper db,ObjectMapper json,boolean benchmarks,boolean compact){
        this(db,json,benchmarks,compact,java.util.Optional.empty());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public TaskHistoryCatalogService(ConfirmedSampleMapper db,ObjectMapper json,
            @org.springframework.beans.factory.annotation.Value("${jia.economy.measured-benchmark-use:false}") boolean benchmarks,
            @org.springframework.beans.factory.annotation.Value("${jia.economy.history-feature-projection:false}") boolean compact,java.util.Optional<ObservationRegistry> registry){
        this.observations=registry.orElse(ObservationRegistry.NOOP);
        this.db=db;this.json=json;this.benchmarks=benchmarks;this.compact=compact;
    }
    @Transactional(propagation=Propagation.NEVER)
    public List<Entry> candidatePage(Task query,Group group,cn.jia.economy.task.TaskCalibrationCatalog.Pin pin,String after){
        if(!benchmarks)return page(query,group,after);
        if(!group.digest().equals(pin.group()))throw new IllegalArgumentException("Wrong benchmark scope");
        var b=db.benchmarkPage(query,pin,after,PAGE);
        if(compact){var projected=projectedPage(query,group,after);
            if(projected!=null)return merge(group,projected,b,e->e.source().sampleId(),e->e);}
        return merge(group,db.history(query,pin.group(),after,PAGE),b,ConfirmedSampleMapper.HistoryRow::id,r->entry(group,r));
    }
    private <T> List<Entry> merge(Group group,List<T> h,List<ConfirmedSampleMapper.HistoryRow> b,
            java.util.function.Function<T,String> id,java.util.function.Function<T,Entry> decode){
        var result=new java.util.ArrayList<Entry>();int i=0,j=0;
        while(result.size()<PAGE && (i<h.size() || j<b.size())){
            boolean takeHistory=j==b.size() || (i<h.size() && id.apply(h.get(i)).compareTo(b.get(j).id())<0);
            result.add(takeHistory?decode.apply(h.get(i++)):entry(group,b.get(j++)));
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
        if(compact){var projected=projectedPage(query,group,after);if(projected!=null)return projected;}
        return db.history(query,group.digest(),after,PAGE).stream().map(r->entry(group,r)).toList();
    }
    // Only unusable derived data falls back. Never suppress database faults or canonical-source failures.
    private List<Entry> projectedPage(Task query,Group group,String after){
        var span=Observation.start("cyf.task.quote.history.projection.page",observations);
        String outcome="error";
        try{
            var rows=db.compactHistory(query,group.digest(),after,PAGE);var result=new java.util.ArrayList<Entry>(rows.size());
            for(var row:rows){
                if(row.metadata()==null){outcome="missing";return null;}
                if(!Integer.valueOf(1).equals(row.sourceValid())){outcome="source_mismatch";return null;}
                try{
                    var meta=json.readValue(row.metadata(),EconomyHistoryFeatureBytes.Metadata.class);
                    var f=EconomyHistoryFeatureBytes.restore(meta,row.vector(),row.featureSha());
                    if(!Group.of(group.execution(),f).equals(group)){outcome="group_mismatch";return null;}
                    result.add(new Entry(new Revision(row.id(),row.revision()),row.taskId(),f));
                }catch(IllegalArgumentException|tools.jackson.core.JacksonException invalid){outcome="invalid_features";return null;}
            }
            outcome=rows.isEmpty()?"empty":"hit";return result;
        }finally{span.lowCardinalityKeyValue("outcome",outcome).stop();}
    }
    private Entry entry(Group group,ConfirmedSampleMapper.HistoryRow r){
            if(r.featuresJson()==null)throw new IllegalStateException("Unresolvable history quote features");
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
        return capsule(original,group,selected,family,partition,row);
    }
    @Override @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public QuotedObservation freezeConfirmedQuote(Task original,Group group,Revision selected,String family,cn.jia.economy.pricing.TaskEstimateEvaluation.Split partition,String expectedQuoteId,String expectedAgentId){
        var row=db.selectedForCalibration(original,group.digest(),selected.sampleId(),selected.revision());
        if(row==null||expectedQuoteId==null||expectedQuoteId.isBlank()||!expectedQuoteId.equals(row.quoteId())
                ||expectedAgentId==null||expectedAgentId.isBlank()||!expectedAgentId.equals(row.agentId()))throw new NotEligible();
        return new QuotedObservation(row.quoteId(),row.agentId(),row.amountWen(),capsule(original,group,selected,family,partition,row));
    }
    private Frozen capsule(Task original,Group group,Revision selected,String family,cn.jia.economy.pricing.TaskEstimateEvaluation.Split partition,ConfirmedSampleMapper.CalibrationRow row){
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
