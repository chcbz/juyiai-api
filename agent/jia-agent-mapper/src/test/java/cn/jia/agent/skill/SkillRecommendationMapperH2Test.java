package cn.jia.agent.skill;

import cn.jia.agent.mapper.skill.SkillManagementMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class SkillRecommendationMapperH2Test {
    @Test void onlySelectedApprovedVersionsOfStillPublishedProductsReturnPublicLabels() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:labels_"+java.util.UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE agent_skill_product(tenant_id VARCHAR(50),client_id VARCHAR(50),product_id VARCHAR(100),current_product_version_id VARCHAR(100),version BIGINT,name VARCHAR(100),description VARCHAR(100),status VARCHAR(20))");
        jdbc.execute("CREATE TABLE agent_skill_product_version(tenant_id VARCHAR(50),client_id VARCHAR(50),product_version_id VARCHAR(100),product_id VARCHAR(100),review_status VARCHAR(20))");
        jdbc.execute("INSERT INTO agent_skill_product VALUES('t','c','p','new',7,'current-label','desc','PUBLISHED'),('t','c','gone','removed',2,'withdrawn','x','RETIRED'),('t','other','p','old',1,'private','x','PUBLISHED')");
        jdbc.execute("INSERT INTO agent_skill_product_version VALUES('t','c','old','p','APPROVED'),('t','c','new','p','APPROVED'),('t','c','unapproved','p','PENDING'),('t','c','removed','gone','APPROVED'),('t','other','old','p','APPROVED')");
        var bean=new SqlSessionFactoryBean();bean.setDataSource(ds);var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);config.addMapper(SkillManagementMapper.class);bean.setConfiguration(config);
        var mapper=new SqlSessionTemplate(java.util.Objects.requireNonNull(bean.getObject())).getMapper(SkillManagementMapper.class);
        assertEquals(java.util.List.of(new SkillManagementMapper.RecommendationProduct("old","p","new",7L,"current-label","desc")),mapper.recommendationProducts("t","c",java.util.List.of("old","unapproved","removed","missing")));
    }
    @Test void activeNativeAndPurchasedAuthorizationsAreScopedWithoutMarketplaceInstallationDependency() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:recommend_"+java.util.UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE agent_skill_managed_state(tenant_id VARCHAR(50),client_id VARCHAR(50),agent_id VARCHAR(100),skill_key VARCHAR(100) DEFAULT 'test',active_installation_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE agent_skill_installation_authorization(tenant_id VARCHAR(50),client_id VARCHAR(50),installation_id VARCHAR(100),package_json VARCHAR(1000))");
        // Deliberately no marketplace installation table: native position/adopted skills are valid.
        jdbc.execute("INSERT INTO agent_skill_managed_state(tenant_id,client_id,agent_id,active_installation_id) VALUES('t','c','a','native'),('t','c','a','market'),('other','c','a','native'),('t','other','a','native'),('t','c','b','other-agent')");
        jdbc.execute("INSERT INTO agent_skill_installation_authorization VALUES('t','c','native','native-package'),('t','c','market','market-package'),('t','c','inactive','old'),('other','c','native','private'),('t','other','native','private'),('t','c','other-agent','private')");
        var bean=new SqlSessionFactoryBean();bean.setDataSource(ds);var config=new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);config.addMapper(SkillManagementMapper.class);bean.setConfiguration(config);
        var mapper=new SqlSessionTemplate(java.util.Objects.requireNonNull(bean.getObject())).getMapper(SkillManagementMapper.class);
        var states=mapper.activeRecommendationStates("t","c",java.util.List.of("a","absent"));
        assertEquals(2,states.size()); assertTrue(states.stream().allMatch(row->"a".equals(row.getAgentId())));
        var rows=mapper.activeRecommendationAuthorizations("t","c",java.util.List.of("a","absent"));
        assertEquals(java.util.List.of("market","native"),rows.stream().map(r->r.getInstallationId()).toList());
        assertEquals(java.util.List.of("market-package","native-package"),rows.stream().map(r->r.getPackageJson()).toList());
        assertTrue(mapper.activeRecommendationAuthorizations("missing","c",java.util.List.of("a")).isEmpty());
        assertTrue(mapper.activeRecommendationAuthorizations("t","c",java.util.List.of("absent")).isEmpty());
    }
}
