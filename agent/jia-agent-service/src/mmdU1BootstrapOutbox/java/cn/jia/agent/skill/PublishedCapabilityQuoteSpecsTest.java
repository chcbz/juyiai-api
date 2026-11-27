package cn.jia.agent.skill;

import cn.jia.agent.mapper.skill.CapabilityQuoteSpecMapper;
import cn.jia.agent.mapper.skill.CapabilityQuoteSpecMapper.*;
import cn.jia.agent.requirement.ConfirmedScope;
import cn.jia.agent.skill.quote.CapabilityQuoteSpec;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PublishedCapabilityQuoteSpecsTest {
    private final CapabilityQuoteSpecMapper mapper=mock(CapabilityQuoteSpecMapper.class);
    private final PublishedCapabilityQuoteSpecs service=new PublishedCapabilityQuoteSpecs(mapper);
    @AfterEach void cleanup(){TransactionSynchronizationManager.setActualTransactionActive(false);}
    private static CapabilityQuoteSpec spec(String id) {
        return new CapabilityQuoteSpec(CapabilityQuoteSpec.SCHEMA,id,"1",Map.of(),
                List.of(new CapabilityQuoteSpec.CostDimension("count",CapabilityQuoteSpec.Source.INPUT_COUNT,null,ConfirmedScope.ValueType.INTEGER,"unit/items",null,Set.of())),
                List.of(new CapabilityQuoteSpec.Bucket("all",Map.of("count",new CapabilityQuoteSpec.Range("0",null,Set.of())))));
    }
    private static StoredSpec stored(String id) {
        var spec=spec(id);return new StoredSpec(id,"1",spec.specSha(),CapabilityQuoteSpecJson.write(spec),"ACTIVE");
    }
    @Test void duplicateVersionsAreReadOnceInBoundedDeterministicBatches() {
        var batches=new ArrayList<List<SpecKey>>();
        when(mapper.published(eq("0"),eq("client"),anyList(),eq(false))).thenAnswer(i->{
            List<SpecKey> batch=i.getArgument(2);batches.add(List.copyOf(batch));return batch.stream().map(k->stored(k.capabilityId())).toList();
        });
        var requested=new ArrayList<SpecKey>();for(int i=128;i>=0;i--)requested.add(new SpecKey("cap-"+i,"1"));requested.addAll(List.copyOf(requested));
        assertEquals(129,service.readActive("0","client",requested).size());assertEquals(2,batches.size());
        assertEquals(128,batches.getFirst().size());assertEquals(1,batches.getLast().size());
        var all=batches.stream().flatMap(List::stream).toList();assertEquals(all.stream().sorted(Comparator.comparing(SpecKey::capabilityId)).toList(),all);
        verify(mapper,times(2)).published(eq("0"),eq("client"),anyList(),eq(false));
    }
    @Test void missingWithdrawnCorruptAndMisboundRowsNeverBecomePriceFallback() {
        var key=new SpecKey("cap","1");
        when(mapper.published("0","client",List.of(key),false)).thenReturn(List.of());
        assertEquals(409,assertThrows(SkillMarketplaceException.class,()->service.readActive("0","client",List.of(key))).status());
        var row=stored("cap");
        for(var bad:List.of(new StoredSpec("cap","1",row.specSha(),row.specJson(),"WITHDRAWN"),
                new StoredSpec("cap","1","0".repeat(64),row.specJson(),"ACTIVE"),
                new StoredSpec("cap","1",stored("other").specSha(),stored("other").specJson(),"ACTIVE"),
                new StoredSpec("cap","1",row.specSha(),row.specJson(),"UNKNOWN"))) {
            when(mapper.published("0","client",List.of(key),false)).thenReturn(List.of(bad));
            assertEquals("WITHDRAWN".equals(bad.status())?409:503,assertThrows(SkillMarketplaceException.class,()->service.readActive("0","client",List.of(key))).status());
        }
        when(mapper.published("0","client",List.of(key),false)).thenReturn(List.of(row,row));
        assertEquals(503,assertThrows(SkillMarketplaceException.class,()->service.readActive("0","client",List.of(key))).status());
    }
    @Test void installationMetadataComesOnlyFromVerifiedActiveSemanticBindings() {
        when(mapper.forApprovedVersion("0","client","version")).thenReturn(List.of());
        assertTrue(service.bindingsForVersion("0","client","version").isEmpty());
        var row=stored("cap");
        when(mapper.forApprovedVersion("0","client","version")).thenReturn(List.of(row));
        assertEquals(List.of(Map.of("capabilityId","cap","capabilityVersion","1","specSha",row.specSha())),service.bindingsForVersion("0","client","version"));
        when(mapper.forApprovedVersion("0","client","version")).thenReturn(List.of(new StoredSpec("cap","1",row.specSha(),row.specJson(),"WITHDRAWN")));
        assertTrue(service.bindingsForVersion("0","client","version").isEmpty());
        when(mapper.forApprovedVersion("0","client","version")).thenReturn(List.of(new StoredSpec("cap","1","0".repeat(64),row.specJson(),"WITHDRAWN")));
        assertEquals(503,assertThrows(SkillMarketplaceException.class,()->service.bindingsForVersion("0","client","version")).status());
    }
    @Test void confirmationUsesSharedLocksAndRejectsUndeclaredUserConstraints() {
        var item=new ConfirmedScope.WorkItem("w","cap","1",Map.of(),Map.of(),List.of(),List.of());
        assertEquals(503,assertThrows(SkillMarketplaceException.class,()->service.validateForConfirmation("0","client",List.of(item))).status());
        verifyNoInteractions(mapper);TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.published(eq("0"),eq("client"),anyList(),eq(true))).thenReturn(List.of(stored("cap")));
        service.validateForConfirmation("0","client",List.of(item));
        var invalid=new ConfirmedScope.WorkItem("w","cap","1",Map.of("unknown",new ConfirmedScope.Scalar(ConfirmedScope.ValueType.INTEGER,"unit/items","1")),Map.of(),List.of(),List.of());
        assertEquals(400,assertThrows(SkillMarketplaceException.class,()->service.validateForConfirmation("0","client",List.of(invalid))).status());
        verify(mapper,times(2)).published(eq("0"),eq("client"),anyList(),eq(true));
    }
}
