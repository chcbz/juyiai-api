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
