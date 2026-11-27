package cn.jia.economy.service.impl;

import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.mapper.ScopedGroupPolicyMapper;
import cn.jia.economy.pricing.GroupMeanPricing;
import cn.jia.economy.pricing.ScopedGroupPolicies;
import cn.jia.economy.service.EconomyScope;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Reads independently approved originals using a policy-independent index. No training, provider
 * calls, approval creation or implicit policy parameters occur in this online consumer. */
@Service
public class DatabaseScopedGroupPolicies implements ScopedGroupPolicies {
    static final String PURPOSE="SCOPED_GROUP_HOLDOUT_RELEASE_V1";
    static final int MAX_BYTES=1048576;
    private static final ObjectMapper JSON=JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(MAX_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    /** Private approved original, not a public client request. Numeric metrics are exact decimals. */
    record Receipt(String purpose,String tenant,String client,String approvalRef,String protocolSha,String evaluationSha,
                   GroupMeanPricing.ScopedPolicy policy,String policySha,String inputSha,String groupSha,
                   List<GroupMeanPricing.ScopeSlot> scopeShape,List<String> bucketIds,Map<String,Object> compatibleConfig,
                   int holdoutCases,int estimatedCases,BigDecimal actualSum,BigDecimal absoluteErrorSum,
                   BigDecimal positiveCostP90RelativeError) {}
    private final ScopedGroupPolicyMapper mapper;
    public DatabaseScopedGroupPolicies(ScopedGroupPolicyMapper mapper){this.mapper=mapper;}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Scoped policy transaction required");}
    private static void domain(EconomyScope scope){Objects.requireNonNull(scope);id(scope.tenantId(),50);id(scope.clientId(),50);}
    private static void id(String value,int max){
        if(value==null || value.isBlank() || !value.equals(value.strip()) || value.codePointCount(0,value.length())>max)throw new IllegalArgumentException("Invalid scoped policy identity");
        CanonicalValueEncoding.utf8(value);
    }
    private static void sha(String value){if(value==null || !value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid scoped policy hash");}
    private static void require(boolean condition){if(!condition)throw new IllegalArgumentException("Invalid approved scoped policy");}
    private static void amount(BigDecimal value){
        require(value!=null && value.signum()>=0 && value.scale()<=30 && value.precision()-value.scale()<=35);
    }
    private static Selected decode(EconomyScope scope,ScopedGroupPolicyMapper.Row row){
        try {
            require(row.body()!=null && row.byteLength()!=null && row.byteLength()>0 && row.byteLength()<=MAX_BYTES);
            require(row.byteLength()==CanonicalValueEncoding.utf8(row.body()).length);
            require(row.approvalSha().equals(GroupPublicationEnrollment.digest(row.body())));
            var r=JSON.readValue(row.body(),Receipt.class);
            require(PURPOSE.equals(r.purpose()) && scope.tenantId().equals(r.tenant()) && scope.clientId().equals(r.client()));
            id(r.approvalRef(),128);sha(r.protocolSha());sha(r.evaluationSha());require(!r.protocolSha().equals(r.evaluationSha()));
            require(r.holdoutCases()>0 && r.estimatedCases()==r.holdoutCases());
            amount(r.actualSum());amount(r.absoluteErrorSum());amount(r.positiveCostP90RelativeError());
            require(r.absoluteErrorSum().multiply(BigDecimal.valueOf(4)).compareTo(r.actualSum())<=0
                    && r.positiveCostP90RelativeError().compareTo(new BigDecimal("0.50"))<=0);
            // With no positive actual cost there is no relative-error denominator: only exact zero
            // error is admissible, and zero explicitly denotes the empty positive-cost population.
            require(r.actualSum().signum()>0 || (r.absoluteErrorSum().signum()==0 && r.positiveCostP90RelativeError().signum()==0));
            var policy=r.policy();require(policy.sha().equals(r.policySha()));
            var group=new GroupMeanPricing.ScopeGroup(policy.featureVersion(),policy.sha(),r.scopeShape(),r.bucketIds(),r.compatibleConfig());
            policy.requireInput(group.input());
            require(group.key().equals(r.groupSha()) && group.input().key().equals(r.inputSha()));
            return new Selected(row.approvalSha(),policy,group);
        }catch(RuntimeException invalid){throw new Corrupt();}
    }
    @Override public Map<GroupMeanPricing.ScopeInput,Selected> read(EconomyScope scope,List<GroupMeanPricing.ScopeInput> inputs,boolean lock){
        domain(scope);if(lock)transaction();
        var wanted=new TreeMap<String,GroupMeanPricing.ScopeInput>();
        for(var input:inputs){var previous=wanted.put(input.key(),input);if(previous!=null && !previous.equals(input))throw new Corrupt();}
        var keys=List.copyOf(wanted.keySet());var result=new LinkedHashMap<GroupMeanPricing.ScopeInput,Selected>();var seen=new HashSet<String>();
        for(int start=0;start<keys.size();start+=128){
            var page=keys.subList(start,Math.min(start+128,keys.size()));
            for(var row:mapper.read(scope.tenantId(),scope.clientId(),page,lock)){
                if(!scope.tenantId().equals(row.tenantId()) || !scope.clientId().equals(row.clientId())
                        || !page.contains(row.inputSha()) || !seen.add(row.inputSha()) || row.withdrawn()==null)throw new Corrupt();
                if(row.withdrawn())continue;
                var selected=decode(scope,row);
                if(!selected.group().input().equals(wanted.get(row.inputSha())) || !selected.group().key().equals(row.groupSha()))throw new Corrupt();
                result.put(wanted.get(row.inputSha()),selected);
            }
        }
        return Collections.unmodifiableMap(result);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Selected select(EconomyScope scope,GroupMeanPricing.ScopeInput input,String approvalSha,String expectedApprovalSha){
        domain(scope);transaction();Objects.requireNonNull(input);sha(approvalSha);if(expectedApprovalSha!=null)sha(expectedApprovalSha);
        var original=mapper.approved(approvalSha);
        if(original==null || Boolean.TRUE.equals(original.withdrawn()))throw new Conflict();
        if(original.withdrawn()==null)throw new Corrupt();
        var selected=decode(scope,original);
        if(!selected.group().input().equals(input))throw new Conflict();
        var current=mapper.lockSelection(scope.tenantId(),scope.clientId(),input.key());
        if(approvalSha.equals(current)){
            var existing=read(scope,List.of(input),true).get(input);
            if(existing==null || !existing.group().key().equals(selected.group().key()))throw new Corrupt();
            return existing;
        }
        if(!Objects.equals(current,expectedApprovalSha))throw new Conflict();
        if(current!=null && mapper.remove(scope.tenantId(),scope.clientId(),input.key(),current)!=1)throw new Conflict();
        var row=new ScopedGroupPolicyMapper.Row(scope.tenantId(),scope.clientId(),input.key(),selected.group().key(),approvalSha,null,null,null);
        try{if(mapper.insert(row)!=1)throw new Conflict();}catch(DuplicateKeyException duplicate){throw new Conflict();}
        return selected;
    }
}
