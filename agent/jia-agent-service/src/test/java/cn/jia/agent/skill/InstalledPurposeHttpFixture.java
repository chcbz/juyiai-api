package cn.jia.agent.skill;

import cn.jia.agent.dao.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.mapper.skill.*;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.*;
import cn.jia.economy.mapper.*;
import cn.jia.economy.service.skill.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated recommendation read fixture: database represents a completed installation, not a simulated purchase.
 * Chat owns the real transient registry, native proof revalidation uses production SQL. No external WS: socket/subscription liveness, catalog declarations and feature availability are fixture inputs. */
public final class InstalledPurposeHttpFixture {
    private static final String HASH="a".repeat(64);
    public final SkillManagementService skills;
    private final SkillRelationshipAuthorizations relationships;
    private final JdbcTemplate db;
    private final String tenant,client,owner;
    private final AgentRuntimeAuthenticationService auth;
    private final cn.jia.chat.handler.SkillExecutionSessionRegistry catalog=new cn.jia.chat.handler.SkillExecutionSessionRegistry();
    public InstalledPurposeHttpFixture(JdbcTemplate db,PlatformTransactionManager tx,String tenant,String client,String owner,AgentRuntimeAuthenticationService auth) throws Exception {
        this.db=db;this.tenant=tenant;this.client=client;this.owner=owner;this.auth=auth;
        for(String ddl:List.of("agent-runtime-session-fence-v1","agent-runtime-v1-schema","economy-v0-skill-marketplace","economy-v0-skill-application","economy-skill-management-sm-c1"))
            new ResourceDatabasePopulator(new ClassPathResource("db/"+ddl+".sql")).execute(db.getDataSource());
        var conf=new com.baomidou.mybatisplus.core.MybatisConfiguration();conf.setMapUnderscoreToCamelCase(true);conf.addMapper(SkillManagementMapper.class);
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(db.getDataSource());factory.setConfiguration(conf);
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(SkillManagementMapper.class);
        var marketplace=mock(SkillMarketplaceService.class);when(marketplace.available(any(HostingRentHttp.Actor.class))).thenReturn(true);when(marketplace.available(tenant,client)).thenReturn(true);
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("catalog",catalog);
        skills=new SkillManagementService(mapper,mock(SkillCatalogMapper.class),mock(SkillInstallationMapper.class),marketplace,mock(SkillTradeService.class),mock(SkillAgentVersions.class),mock(AgentRuntimeDao.class),mock(AgentRuntimeV1InstallationDao.class),auth,mock(AgentIdentityService.class),beans.getBeanProvider(SkillPackages.class),beans.getBeanProvider(SkillInstallCommandWriter.class),beans.getBeanProvider(AgentManagedSessionLookup.class),beans.getBeanProvider(SkillCapabilityLookup.class),tx,mock(cn.jia.agent.hosting.HostingRentWorkAdmission.class));
        relationships=new SkillRelationshipAuthorizations(mapper,true);skills.setRelationshipAuthorizations(relationships);
        db.update("INSERT INTO agent_skill_product(tenant_id,client_id,product_id,name,description,status,current_product_version_id,create_time,update_time) VALUES(?,?,'rec-product','合同审查','核对合同条款','PUBLISHED','rec-version',1,1)",tenant,client);
        db.update("INSERT INTO agent_skill_product_version(tenant_id,client_id,product_version_id,product_id,version_sequence,skill_key,skill_version,price_wen,package_sha256,package_size,approved_permissions_manifest,approved_permissions_sha256,create_time) VALUES(?,?,'rec-version','rec-product',1,'rec-skill','1.0.0',0,UNHEX(?),1,'[]',UNHEX(?),1)",tenant,client,HASH,HASH);
    }
    public void installed(String agent) {
        String install="si_"+agent,rt="rt_"+agent;
        String pkg="{\"skillInstallationId\":\""+install+"\",\"productVersionId\":\"rec-version\",\"skillVersion\":\"1.0.0\",\"packageSha256\":\""+HASH+"\",\"manifestSha256\":null,\"byteLength\":\"1\",\"kind\":\"INSTRUCTION\"}";
        db.update("INSERT INTO agent_skill_managed_state(tenant_id,client_id,agent_id,skill_key,skill_revision,active_installation_id,active_policy_revision,desired_enabled,admission_enabled) VALUES(?,?,?,'rec-skill',1,?,1,TRUE,FALSE)",tenant,client,agent,install);
        db.update("INSERT INTO agent_skill_installation_authorization(tenant_id,client_id,installation_id,change_id,entitlement_id,package_json,details_json,permissions_json,permissions_sha256,runtime_installation_id,runtime_host_id) VALUES(?,?,?,?,?,?,'{}','[]',?,?,'host')",tenant,client,install,"change_"+agent,"ent_"+agent,pkg,HASH,rt);
        assertEquals(1,db.update("UPDATE agent_runtime SET status='online',runtime_installation_id=?,runtime_host_id='host',runtime_instance_id='instance',runtime_session_generation=1 WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND agent_id=?",rt,tenant,client,owner,agent));
        db.update("INSERT INTO agent_runtime_v1_installation(installation_id,canonical_agent_id,manifest_version,manifest_sha256,enrollment_secret_hash,enrollment_expires_at,status,tenant_id,client_id) VALUES(?,?,'test',?,UNHEX(?),1,'ACTIVE',?,?)",rt,agent,HASH,HASH,tenant,client);
        assertEquals(1,db.update("UPDATE agent_runtime SET token_hash=? WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND agent_id=?","urs1:"+HASH+":1:1",tenant,client,owner,agent));
        registerSession(agent,1L);
    }
    public void registerSession(String agent,long generation){
        String install="si_"+agent,rt="rt_"+agent;
        var proof=new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope(tenant,client,owner,agent,"instance"),rt,"host",generation,HASH,1L,1L);
        auth.bind("fixture-session-"+generation+agent,proof,()->true);
        var fence=new AgentManagedSessionLookup.SessionFence(rt,"host","instance",generation);
        var fact=new SkillCapabilityLookup.RuntimeSkill("rec-skill","1.0.0",HASH,null,null,SkillCapabilityLookup.Readiness.NOT_EXECUTABLE,null,null,null,install,"rec-version","INSTRUCTION","1",true,1L);
        String socket="fixture-session-"+generation+agent;
        catalog.register(socket,new SkillCapabilityLookup.Scope(tenant,client,owner,agent),fence,new SkillCapabilityLookup.Catalog("1",List.of(fact)),
                ()->auth.isCurrentBinding(socket,tenant,client,owner,agent,"instance"));
    }
    /** Synthetic settled rows exercise the read contract only; no money operations or historical pricing samples. */
    public void retained(String agent) throws Exception {
        String lease="lease_"+agent,intent="intent_"+agent,quote="quote_"+agent,install="si_"+agent;
        var ref=new cn.jia.agent.hosting.ManagedHostingProvisioner.ManagedSkillReference(install,"rec-version","1.0.0","INSTRUCTION",HASH,null);
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(List.of(new cn.jia.agent.hosting.ManagedHostingProvisioner.InstalledSkill("rec-skill","MANAGED",HASH,true,ref)));
        long binding=db.queryForObject("SELECT binding_id FROM agent_runtime WHERE tenant_id=? AND client_id=? AND agent_id=?",Long.class,tenant,client,agent);
        db.update("INSERT INTO economy_hosting_rent_quote(quote_id,quote_purpose,plan_id,plan_version,amount_wen,recurring_amount_wen,initial_skill_count,inventory_snapshot_json,period_seconds,principal_type,principal_id,persona_code,agent_id,idempotency_key,request_hash,expires_at,tenant_id,client_id,create_time) VALUES(?,'INITIAL','fixture',1,1,1,1,?,1,'USER','fixture-user','fixture',?,'retained-key',UNHEX(?),2,?,?,1)",quote,json,agent,HASH,tenant,client);
        db.update("INSERT INTO economy_hosting_lease(lease_id,principal_type,principal_id,persona_code,agent_id,binding_id,plan_id,plan_version,amount_wen,period_seconds,status,latest_intent_id,version,tenant_id,client_id,create_time,update_time) VALUES(?,'USER','fixture-user','fixture',?,?,'fixture',1,1,1,'ACTIVE',?,1,?,?,1,1)",lease,agent,Long.toString(binding),intent,tenant,client);
        db.update("INSERT INTO economy_hosting_provisioning_intent(intent_id,lease_id,quote_id,quote_purpose,principal_type,principal_id,persona_code,agent_id,amount_wen,period_seconds,status,settlement_kind,capture_transaction_id,escrow_version,version,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,'INITIAL','USER','fixture-user','fixture',?,1,1,'ACTIVE','PAID',NULL,1,1,?,?,1,1)",intent,lease,quote,agent,tenant,client);
        assertEquals(1,db.update("UPDATE agent_skill_installation_authorization SET runtime_host_id='previous-host' WHERE tenant_id=? AND client_id=? AND installation_id=?",tenant,client,install));
        var right=new SkillManagementMapper.InventoryKey(agent,owner);var other=new SkillManagementMapper.InventoryKey("other",owner);
        assertTrue(relationships.readMany(tenant,client,List.of(right,other)).isEmpty());
        assertEquals(1,db.update("UPDATE economy_hosting_provisioning_intent SET capture_transaction_id='synthetic-capture' WHERE tenant_id=? AND client_id=? AND intent_id=? AND capture_transaction_id IS NULL",tenant,client,intent));
        assertEquals(java.util.Set.of(right),relationships.readMany(tenant,client,List.of(right,other)).keySet());
        assertTrue(relationships.readMany(tenant,client,List.of(new SkillManagementMapper.InventoryKey(agent,owner+"-other"),other)).isEmpty());
    }
    public void withdraw(){assertEquals(1,db.update("UPDATE agent_skill_product SET status='RETIRED',version=version+1 WHERE tenant_id=? AND client_id=? AND product_id='rec-product' AND status='PUBLISHED'",tenant,client));}
}
