package cn.jia.economy.service.impl;

import cn.jia.economy.mapper.GroupBaselineMapper;
import cn.jia.economy.pricing.GroupMeanPricing;
import cn.jia.economy.task.TaskQuoteExperiment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskQuoteExperimentReader implements TaskQuoteExperiment {
    private final GroupBaselineMapper db;private final ObjectMapper json;
    public TaskQuoteExperimentReader(GroupBaselineMapper db,ObjectMapper json){this.db=db;this.json=json;}
    record Receipt(String purpose,String approvalRef,String tenant,String client,String owner,String taskId,String taskVersion,long requirementRevision,String requirementSha,String inputSha,
        String operation,Integer requestedDeliverables,Boolean artifactsRequired,Boolean multiple,String policySha,String featureVersion,String pricingRule,String configRule,Integer minSupport,GroupMeanPricing.Buckets buckets){}
    public Approved read(Subject s,String sha){var raw=db.experiment(s,sha);if(raw==null)return null;
        if(!sha.equals(GroupPublicationEnrollment.digest(raw)))throw new IllegalStateException("Damaged quote experiment receipt");
        var r=json.readValue(raw,Receipt.class);if(r.approvalRef()==null||r.approvalRef().isBlank()||r.artifactsRequired()==null||r.multiple()==null||r.minSupport()==null||r.buckets()==null||!java.util.Set.of("edit","generate").contains(r.operation())||!r.operation().equals(r.buckets().operation())||r.requestedDeliverables()!=null&&r.requestedDeliverables()<=0)return null;
        var policy=new GroupMeanPricing.Policy(r.featureVersion(),r.pricingRule(),r.configRule(),r.buckets(),r.minSupport());
        return policy.sha().equals(r.policySha())?new Approved(r.operation(),r.requestedDeliverables(),(r.artifactsRequired()?2:0)+(r.multiple()?1:0),policy):null;
    }
    public boolean current(Subject s,String sha){
        try{return sha.equals(db.lockExperiment(s,sha));}
        catch(org.springframework.dao.DataAccessException e){for(Throwable cause=e;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql&&sql.getErrorCode()==3572)return false;throw e;}
    }
}
