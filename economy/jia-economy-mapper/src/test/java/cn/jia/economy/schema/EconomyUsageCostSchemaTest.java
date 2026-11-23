package cn.jia.economy.schema;

import cn.jia.economy.mapper.EconomyUsageCostMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EconomyUsageCostSchemaTest {
    @Test void costSchemaIsSeparateRmbAndHasNoBusinessCheckOrWalletReference() throws Exception {
        String ddl = Files.readString(Path.of("src/main/resources/db/economy-usage-cost.sql")).toLowerCase(Locale.ROOT);
        assertEquals(3, ddl.split("create table if not exists", -1).length - 1);
        assertEquals(3, ddl.split("decimal\\(30,6\\)", -1).length - 1);
        assertTrue(ddl.contains("unique key uk_usage_revision_receipt"));
        assertEquals(1, ddl.split("task_id varchar\\(100\\) default null", -1).length - 1);
        assertEquals(1, ddl.split("owner_jiacn varchar\\(100\\) default null", -1).length - 1);
        assertEquals(1, ddl.split("owner_jiacn varchar\\(100\\) not null", -1).length - 1);
        assertTrue(ddl.contains("foreign key (tenant_id, client_id, attempt_id)"));
        for (String forbidden : new String[]{"check (", "create trigger", "double", "float", "amount_wen", "economy_account", "drop table"})
            assertFalse(ddl.contains(forbidden), forbidden);
    }
    @Test void revisionMapperIsAppendOnly() {
        int appends=0;
        for (var method : EconomyUsageCostMapper.class.getDeclaredMethods()) {
            var select=method.getAnnotation(org.apache.ibatis.annotations.Select.class);
            var insert=method.getAnnotation(org.apache.ibatis.annotations.Insert.class);
            var update=method.getAnnotation(org.apache.ibatis.annotations.Update.class);
            assertNull(method.getAnnotation(org.apache.ibatis.annotations.Delete.class));
            assertTrue(select!=null||insert!=null||update!=null,method.getName());
            String sql=String.join(" ",select!=null?select.value():insert!=null?insert.value():update.value()).strip().toLowerCase(Locale.ROOT);
            if(!sql.contains("economy_usage_revision"))continue;
            if(select!=null){assertTrue(sql.startsWith("select "));continue;}
            assertNotNull(insert,method.getName());assertTrue(sql.startsWith("insert into economy_usage_revision("));
            assertFalse(sql.contains("on duplicate key"));appends++;
        }
        assertEquals(1,appends);
    }
}
