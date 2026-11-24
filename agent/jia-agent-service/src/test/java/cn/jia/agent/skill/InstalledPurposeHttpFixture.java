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
 * Only the live runtime proof/catalog and feature availability are supplied at the service boundary. */
public final class InstalledPurposeHttpFixture {
    private static final String HASH="a".repeat(64);
    public final SkillManagementService skills;
    private final JdbcTemplate db;
    private final String tenant,client,owner;
    private final AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
    private final SkillCapabilityLookup catalog=mock(SkillCapabilityLookup.class);
    public InstalledPurposeHttpFixture(JdbcTemplate db,PlatformTransactionManager tx,String tenant,String client,String owner) throws Exception {
        this.db=db;this.tenant=tenant;this.client=client;this.owner=owner;
        for(String ddl:List.of("agent-runtime-session-fence-v1","economy-v0-skill-marketplace","economy-v0-skill-application","economy-skill-management-sm-c1"))
            new ResourceDatabasePopulator(new ClassPathResource("db/"+ddl+".sql")).execute(db.getDataSource());
        var conf=new com.baomidou.mybatisplus.core.MybatisConfiguration();conf.setMapUnderscoreToCamelCase(true);conf.addMapper(SkillManagementMapper.class);
        var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(db.getDataSource());factory.setConfiguration(conf);
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(SkillManagementMapper.class);
        var marketplace=mock(SkillMarketplaceService.class);when(marketplace.available(any(HostingRentHttp.Actor.class))).thenReturn(true);when(marketplace.available(tenant,client)).thenReturn(true);
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();beans.registerSingleton("catalog",catalog);
        skills=new SkillManagementService(mapper,mock(SkillCatalogMapper.class),mock(SkillInstallationMapper.class),marketplace,mock(SkillTradeService.class),mock(SkillAgentVersions.class),mock(AgentRuntimeDao.class),mock(AgentRuntimeV1InstallationDao.class),auth,mock(AgentIdentityService.class),beans.getBeanProvider(SkillPackages.class),beans.getBeanProvider(SkillInstallCommandWriter.class),beans.getBeanProvider(AgentManagedSessionLookup.class),beans.getBeanProvider(SkillCapabilityLookup.class),tx,mock(cn.jia.agent.hosting.HostingRentWorkAdmission.class));
        skills.setRelationshipAuthorizations(new SkillRelationshipAuthorizations(mapper,true));
        db.update("INSERT INTO agent_skill_product(tenant_id,client_id,product_id,name,description,status,current_product_version_id,create_time,update_time) VALUES(?,?,'rec-product','合同审查','核对合同条款','PUBLISHED','rec-version',1,1)",tenant,client);
        db.update("INSERT INTO agent_skill_product_version(tenant_id,client_id,product_version_id,product_id,version_sequence,skill_key,skill_version,price_wen,package_sha256,package_size,approved_permissions_manifest,approved_permissions_sha256,create_time) VALUES(?,?,'rec-version','rec-product',1,'rec-skill','1.0.0',0,UNHEX(?),1,'[]',UNHEX(?),1)",tenant,client,HASH,HASH);
    }
    public void installed(String agent) {
        String install="si_"+agent,rt="rt_"+agent;
        String pkg="{\"skillInstallationId\":\""+install+"\",\"productVersionId\":\"rec-version\",\"skillVersion\":\"1.0.0\",\"packageSha256\":\""+HASH+"\",\"manifestSha256\":null,\"byteLength\":\"1\",\"kind\":\"INSTRUCTION\"}";
        db.update("INSERT INTO agent_skill_managed_state(tenant_id,client_id,agent_id,skill_key,skill_revision,active_installation_id,active_policy_revision,desired_enabled,admission_enabled) VALUES(?,?,?,'rec-skill',1,?,1,TRUE,FALSE)",tenant,client,agent,install);
        db.update("INSERT INTO agent_skill_installation_authorization(tenant_id,client_id,installation_id,change_id,entitlement_id,package_json,details_json,permissions_json,permissions_sha256,runtime_installation_id,runtime_host_id) VALUES(?,?,?,?,?,?,'{}','[]',?,?,'host')",tenant,client,install,"change_"+agent,"ent_"+agent,pkg,HASH,rt);
        assertEquals(1,db.update("UPDATE agent_runtime SET status='online',runtime_installation_id=?,runtime_host_id='host',runtime_instance_id='instance',runtime_session_generation=1 WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND agent_id=?",rt,tenant,client,owner,agent));
        var proof=new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope(tenant,client,owner,agent,"instance"),rt,"host",1L,HASH,1L,1L);
        when(auth.currentRegisteredProof(tenant,client,agent)).thenReturn(proof);
        var fence=new AgentManagedSessionLookup.SessionFence(rt,"host","instance",1L);
        var fact=new SkillCapabilityLookup.RuntimeSkill("rec-skill","1.0.0",HASH,null,null,SkillCapabilityLookup.Readiness.NOT_EXECUTABLE,null,null,null,install,"rec-version","INSTRUCTION","1",true,1L);
        when(catalog.lookup(new SkillCapabilityLookup.Scope(tenant,client,owner,agent),fence)).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session",fence,new SkillCapabilityLookup.Catalog("1",List.of(fact))));
    }
    public void withdraw(){assertEquals(1,db.update("UPDATE agent_skill_product SET status='RETIRED',version=version+1 WHERE tenant_id=? AND client_id=? AND product_id='rec-product' AND status='PUBLISHED'",tenant,client));}
}
