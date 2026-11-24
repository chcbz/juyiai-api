package cn.jia.economy.task;

import cn.jia.economy.pricing.TaskEstimateFeatures;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Private Economy estimator input. Scope comes from an already authorized task, never the browser. */
public interface TaskHistoryCatalog {
    int PAGE=64;
    record Group(TaskBillingService.MeasuredGroup execution,String featureVersion,String templateVersion,TaskEstimateFeatures.Identity embedding){
        public static Group of(TaskBillingService.MeasuredGroup execution,TaskEstimateFeatures query){
            return new Group(execution,query.featureVersion(),query.templateVersion(),query.embeddingIdentity());
        }
        public cn.jia.economy.pricing.TaskEstimateCalibration.PrivateGroup privateCalibrationGroup(TaskBillingService.Task authorized){
            return new cn.jia.economy.pricing.TaskEstimateCalibration.PrivateGroup(authorized.scope().tenantId(),authorized.scope().clientId(),authorized.ownerJiacn(),digest());
        }
        /** No payer, role name, feature values, historic unit prices or task-specific request hash in the compatibility key. */
        public String digest(){try{
            var h=MessageDigest.getInstance("SHA-256");
            try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),h))){
                for(var value:List.of("TASK_HISTORY_GROUP_V1",execution.dispatchGroupVersion(),execution.executionConfigSha256(),execution.localRouteSha256(),
                        execution.routeVersion(),execution.toolConfigSha256(),execution.capturePolicyVersion(),featureVersion,templateVersion,embedding.providerConfigVersion(),embedding.model())){
                    var bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
                }
                out.writeInt(embedding.dimensions());
            }return HexFormat.of().formatHex(h.digest());
        }catch(IOException|NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    }
    record Revision(String sampleId,long revision){}
    record Entry(Revision source,String sourceTaskId,TaskEstimateFeatures features){}
    List<Entry> page(TaskBillingService.Task query,Group group,String afterSampleId);
    /** Read only the selected immutable projection while still current. Null = revision became unavailable, not zero cost. */
    List<cn.jia.economy.pricing.MeasuredTaskRepricing.Fact> quantities(TaskBillingService.Task query,Group group,Revision selected);
    /** Under caller's existing task transaction. Holds a read lock until quote commit; acquire selected ids in sorted order. */
    boolean lockCurrent(TaskBillingService.Task query,Group group,Revision selected);
}
