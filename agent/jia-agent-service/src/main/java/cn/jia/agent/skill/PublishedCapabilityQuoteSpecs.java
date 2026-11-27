package cn.jia.agent.skill;

import cn.jia.agent.mapper.skill.CapabilityQuoteSpecMapper;
import cn.jia.agent.mapper.skill.CapabilityQuoteSpecMapper.SpecKey;
import cn.jia.agent.requirement.ConfirmedScope.WorkItem;
import cn.jia.agent.skill.quote.CapabilityQuoteSpec;
import cn.jia.core.util.CanonicalValueEncoding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Authority for published semantic versions, not for task access or Agent execution permission. */
@Service
public final class PublishedCapabilityQuoteSpecs {
    public static final int READ_BATCH_SIZE=128;
    private final CapabilityQuoteSpecMapper mapper;
    public PublishedCapabilityQuoteSpecs(CapabilityQuoteSpecMapper mapper) {this.mapper=Objects.requireNonNull(mapper);}

    /** Immutable package metadata, not an execution grant. A skill without cost declarations remains installable. */
    public List<Map<String,String>> bindingsForVersion(String tenant,String client,String productVersion) {
        return bindingsForVersions(tenant,client,List.of(productVersion)).get(productVersion);
    }

    /** One bounded read per 128 distinct approved versions, including explicit empty results.
     * Registration/quote callers still own identity fencing and commit-time status revalidation. */
    public Map<String,List<Map<String,String>>> bindingsForVersions(String tenant,String client,Collection<String> requested) {
        require(tenant!=null&&!tenant.isBlank()&&client!=null&&!client.isBlank(),400,"BAD_REQUEST");
        var versions=requested.stream().distinct().sorted(CanonicalValueEncoding::compareUtf8).toList();
        var result=new HashMap<String,List<Map<String,String>>>();
        for(int start=0;start<versions.size();start+=READ_BATCH_SIZE) {
            var batch=versions.subList(start,Math.min(start+READ_BATCH_SIZE,versions.size()));
            var decoded=new HashMap<CapabilityQuoteSpecMapper.StoredSpec,CapabilityQuoteSpec>();
            var seen=new HashMap<String,Set<SpecKey>>();
            for(var version:batch){result.put(version,new ArrayList<>());seen.put(version,new HashSet<>());}
            for(var row:mapper.forApprovedVersions(tenant,client,batch)) {
                var keys=seen.get(row.productVersionId());
                require(keys!=null&&keys.add(new SpecKey(row.capabilityId(),row.version()))
                        &&keys.size()<=CapabilityQuoteSpecJson.MAX_SPECS,503,"CAPABILITY_QUOTE_SPEC_CORRUPT");
                var stored=row.specification();var spec=decoded.get(stored);
                if(spec==null) {
                    try {spec=CapabilityQuoteSpecJson.readStored(row.specJson(),row.specSha());}
                    catch(IllegalStateException corrupt){throw new SkillMarketplaceException(503,"CAPABILITY_QUOTE_SPEC_CORRUPT");}
                    require(spec.capabilityId().equals(row.capabilityId())&&spec.version().equals(row.version())
                            &&Set.of("ACTIVE","WITHDRAWN").contains(row.status()),503,"CAPABILITY_QUOTE_SPEC_CORRUPT");
                    decoded.put(stored,spec);
                }
                if("ACTIVE".equals(row.status()))result.get(row.productVersionId()).add(Map.of(
                        "capabilityId",row.capabilityId(),"capabilityVersion",row.version(),"specSha",row.specSha()));
            }
        }
        result.replaceAll((version,bindings)->bindings.stream().sorted((a,b)->{
            int c=CanonicalValueEncoding.compareUtf8(a.get("capabilityId"),b.get("capabilityId"));
            return c==0?CanonicalValueEncoding.compareUtf8(a.get("capabilityVersion"),b.get("capabilityVersion")):c;
        }).toList());
        return Map.copyOf(result);
    }

    /** New confirmations lock the adopted rows through the original task transaction. Historical replay skips this. */
    public void validateForConfirmation(String tenant,String client,List<WorkItem> items) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),503,"CAPABILITY_QUOTE_SPEC_TRANSACTION_REQUIRED");
        var specs=read(tenant,client,items.stream().map(i->new SpecKey(i.capabilityId(),i.capabilityVersion())).toList(),true);
        for(var item:items) {
            try {specs.get(new SpecKey(item.capabilityId(),item.capabilityVersion())).validateWorkItem(item);}
            catch(IllegalArgumentException invalid){throw new SkillMarketplaceException(400,"REQUIREMENT_SCOPE_INVALID");}
        }
    }

    /** Locked status revalidation belongs to the eventual quote finish transaction, not to this prepare read. */
    public Map<SpecKey,CapabilityQuoteSpec> readActive(String tenant,String client,List<SpecKey> keys) {
        return read(tenant,client,keys,false);
    }
    private Map<SpecKey,CapabilityQuoteSpec> read(String tenant,String client,List<SpecKey> requested,boolean locking) {
        require(tenant!=null && !tenant.isBlank() && client!=null && !client.isBlank(),400,"BAD_REQUEST");
        var keys=requested.stream().distinct().sorted((a,b)->{int c=CanonicalValueEncoding.compareUtf8(a.capabilityId(),b.capabilityId());
            return c==0?CanonicalValueEncoding.compareUtf8(a.version(),b.version()):c;}).toList();
        var result=new HashMap<SpecKey,CapabilityQuoteSpec>();
        for(int start=0;start<keys.size();start+=READ_BATCH_SIZE) {
            var batch=keys.subList(start,Math.min(start+READ_BATCH_SIZE,keys.size()));
            for(var stored:mapper.published(tenant,client,batch,locking)) {
                var key=new SpecKey(stored.capabilityId(),stored.version());
                require(batch.contains(key) && !result.containsKey(key),503,"CAPABILITY_QUOTE_SPEC_CORRUPT");
                require("ACTIVE".equals(stored.status()) || "WITHDRAWN".equals(stored.status()),503,"CAPABILITY_QUOTE_SPEC_CORRUPT");
                require("ACTIVE".equals(stored.status()),409,"CAPABILITY_QUOTE_SPEC_UNAVAILABLE");
                CapabilityQuoteSpec spec;
                try {spec=CapabilityQuoteSpecJson.readStored(stored.specJson(),stored.specSha());}
                catch(IllegalStateException corrupt){throw new SkillMarketplaceException(503,"CAPABILITY_QUOTE_SPEC_CORRUPT");}
                require(spec.capabilityId().equals(key.capabilityId()) && spec.version().equals(key.version()),503,"CAPABILITY_QUOTE_SPEC_CORRUPT");
                result.put(key,spec);
            }
        }
        require(result.size()==keys.size(),409,"CAPABILITY_QUOTE_SPEC_UNAVAILABLE");return Map.copyOf(result);
    }
}
