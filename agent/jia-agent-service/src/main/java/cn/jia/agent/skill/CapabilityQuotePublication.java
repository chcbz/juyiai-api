package cn.jia.agent.skill;

import cn.jia.agent.mapper.skill.CapabilityQuoteSpecMapper;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.agent.skill.quote.CapabilityQuoteSpec;
import cn.jia.core.util.CanonicalValueEncoding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Participates in the original authorized skill publication transaction, never opens another one. */
@Service
public final class CapabilityQuotePublication {
    public static final int MAX_SPECS=CapabilityQuoteSpecJson.MAX_SPECS;
    public static final int MAX_TOTAL_BYTES=CapabilityQuoteSpecJson.MAX_LIST_BYTES;
    private final CapabilityQuoteSpecMapper mapper;
    public CapabilityQuotePublication(CapabilityQuoteSpecMapper mapper) {this.mapper=Objects.requireNonNull(mapper);}
    private record Declaration(CapabilityQuoteSpec spec,String json,String sha) {
        // Only this class creates prepared declarations; no caller-provided hash or serialized payload.
        private static Declaration of(CapabilityQuoteSpec spec) {
            return new Declaration(spec,CapabilityQuoteSpecJson.write(spec),spec.specSha());
        }
    }
    public static final class Prepared {
        private final List<Declaration> declarations;
        private Prepared(List<Declaration> declarations) {this.declarations=List.copyOf(declarations);}
    }
    public static Prepared prepare(List<CapabilityQuoteSpec> specs) {
        require(specs!=null && specs.size()<=MAX_SPECS,400,"CAPABILITY_QUOTE_SPEC_INVALID");
        var result=new ArrayList<Declaration>();var keys=new HashSet<List<String>>();int bytes=0;
        for(var spec:specs) {
            require(spec!=null && keys.add(List.of(spec.capabilityId(),spec.version())),400,"CAPABILITY_QUOTE_SPEC_INVALID");
            Declaration declaration;
            try {declaration=Declaration.of(spec);}catch(IllegalArgumentException invalid){throw new SkillMarketplaceException(400,"CAPABILITY_QUOTE_SPEC_INVALID");}
            bytes=Math.addExact(bytes,CanonicalValueEncoding.utf8(declaration.json()).length);
            require(bytes<=MAX_TOTAL_BYTES,400,"CAPABILITY_QUOTE_SPEC_INVALID");result.add(declaration);
        }
        result.sort((a,b)->{int c=CanonicalValueEncoding.compareUtf8(a.spec().capabilityId(),b.spec().capabilityId());
            return c==0?CanonicalValueEncoding.compareUtf8(a.spec().version(),b.spec().version()):c;});
        return new Prepared(result);
    }

    /** Caller owns the product lock and has already inserted/verified the approved package version. */
    public void bindWithinPublication(Scope actor,String productVersionId,Prepared prepared,boolean replay) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),503,"SKILL_PUBLICATION_TRANSACTION_REQUIRED");
        Objects.requireNonNull(actor);Objects.requireNonNull(prepared);
        var actual=mapper.lockBindings(actor.tenantId(),actor.clientId(),productVersionId);
        var expected=prepared.declarations.stream().map(d->new CapabilityQuoteSpecMapper.Binding(
                d.spec().capabilityId(),d.spec().version(),d.sha())).toList();
        if(replay) {
            require(new HashSet<>(actual).equals(new HashSet<>(expected)) && actual.size()==expected.size(),409,"SKILL_VERSION_IMMUTABLE");
            // Historical replay does not revive a withdrawn semantic version.
            return;
        }
        require(actual.isEmpty(),409,"SKILL_VERSION_IMMUTABLE");
        for(var declaration:prepared.declarations) {
            var spec=declaration.spec();var stored=mapper.lockSpec(actor.tenantId(),actor.clientId(),spec.capabilityId(),spec.version());
            if(stored==null)one(mapper.insertSpec(actor.tenantId(),actor.clientId(),spec.capabilityId(),spec.version(),declaration.sha(),declaration.json()));
            else {
                CapabilityQuoteSpec verified;
                try {verified=CapabilityQuoteSpecJson.readStored(stored.specJson(),stored.specSha());}
                catch(IllegalStateException corrupt){throw new SkillMarketplaceException(503,"CAPABILITY_QUOTE_SPEC_CORRUPT");}
                require(verified.capabilityId().equals(spec.capabilityId()) && verified.version().equals(spec.version())
                        && stored.specSha().equals(declaration.sha()),409,"CAPABILITY_QUOTE_SPEC_IMMUTABLE");
                require(Set.of("ACTIVE","WITHDRAWN").contains(stored.status()),503,"CAPABILITY_QUOTE_SPEC_CORRUPT");
                require("ACTIVE".equals(stored.status()),409,"CAPABILITY_QUOTE_SPEC_WITHDRAWN");
            }
            one(mapper.insertBinding(actor.tenantId(),actor.clientId(),productVersionId,spec.capabilityId(),spec.version(),declaration.sha()));
        }
    }
    private static void one(int affected) {require(affected==1,409,"SKILL_PRODUCT_CHANGED");}
}
