package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.PersonalWorkspaceExecutionDaoImpl;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionInputMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PersonalWorkspaceExecutionMapperContractTest {
    @Test
    void historyUsesExactOwnerKeysetBinaryDescendingAndSummaryOnlyProjection() throws Exception {
        Method method = PersonalWorkspaceExecutionMapper.class.getDeclaredMethod(
                "listHistory", String.class, String.class, String.class,
                Long.class, String.class, int.class);
        String sql = sql(method);

        assertTrue(sql.startsWith("<script> select tenant_id, client_id, owner_jiacn, execution_id,"));
        assertFalse(sql.contains("select *"));
        for (String sensitive : new String[] {" instruction", "lease_token", "storage_uri",
                "idempotency_key", "request_hash", "revoke_idempotency_key"}) {
            assertFalse(sql.substring(0, sql.indexOf(" from ")).contains(sensitive), sensitive);
        }
        for (String exact : new String[] {"tenant_id", "client_id", "owner_jiacn"}) {
            assertTrue(sql.contains("cast(" + exact + " as binary)=cast(#{"), exact);
            assertTrue(sql.contains("octet_length(" + exact + ")=octet_length(#{"), exact);
        }
        assertTrue(sql.contains("created_at &lt; #{beforecreatedat}"));
        assertTrue(sql.contains("created_at=#{beforecreatedat}"));
        assertTrue(sql.contains("cast(execution_id as binary) &lt; cast(#{beforeexecutionid} as binary)"));
        assertTrue(sql.contains("order by created_at desc, cast(execution_id as binary) desc"));
        assertTrue(sql.contains("limit #{limit}"));
    }

    @Test
    void requestLookupIsExactAndExcludesInstructionAndRuntimeCredentials() throws Exception {
        Method method = PersonalWorkspaceExecutionMapper.class.getDeclaredMethod(
                "findRequestByIdempotency", String.class, String.class, String.class, String.class);
        String sql = sql(method);
        String projection = sql.substring(0, sql.indexOf(" from "));

        assertFalse(projection.contains("*"));
        assertFalse(projection.contains("instruction"));
        assertFalse(projection.contains("lease_token"));
        assertFalse(projection.contains("lease_work_item_version"));
        assertFalse(projection.contains("lease_expires_at"));
        assertFalse(projection.contains("request_hash"));
        for (String exact : new String[] {"tenant_id", "client_id", "owner_jiacn", "idempotency_key"}) {
            assertTrue(sql.contains("cast(" + exact + " as binary)=cast(#{"), exact);
            assertTrue(sql.contains("octet_length(" + exact + ")=octet_length(#{"), exact);
        }
        assertTrue(sql.contains("limit 1"));
    }

    @Test
    void daoKeepsPairedCursorAndFullLimitPlusOneBound() {
        PersonalWorkspaceExecutionMapper mapper = mock(PersonalWorkspaceExecutionMapper.class);
        PersonalWorkspaceExecutionDaoImpl dao = new PersonalWorkspaceExecutionDaoImpl(
                mapper, mock(PersonalWorkspaceExecutionInputMapper.class),
                mock(PersonalWorkspaceExecutionOutputMapper.class));

        dao.listHistory("0", "client-a", "owner-a", 1000L, "pwe_1", 101);
        verify(mapper).listHistory("0", "client-a", "owner-a", 1000L, "pwe_1", 101);
        assertThrows(IllegalArgumentException.class,
                () -> dao.listHistory("0", "client-a", "owner-a", 1000L, null, 20));
        assertThrows(IllegalArgumentException.class,
                () -> dao.listHistory("0", "client-a", "owner-a", null, "pwe_1", 20));
        assertThrows(IllegalArgumentException.class,
                () -> dao.listHistory("0", "client-a", "owner-a", null, null, 102));
    }

    @Test
    void nativeQueueScopesPrivateAndTaskAuthorityBeforePaging() throws Exception {
        String sql = sql(PersonalWorkspaceExecutionMapper.class.getDeclaredMethod(
                "listQueuedConversationsByTarget", String.class, String.class, String.class,
                String.class, Long.class, String.class, int.class));
        String admission = sql.substring(0, sql.indexOf(" order by "));
        assertTrue(admission.contains("execution_protocol_version=6"));
        assertFalse(admission.contains("execution_protocol_version=5"));
        assertTrue(admission.contains("execution_mode='conversation'"));
        assertTrue(admission.contains("conversation_provider_started_at is null"));
        int privateStart = admission.indexOf("business_scope='private'");
        int taskStart = admission.indexOf("business_scope='task'");
        assertTrue(privateStart > 0 && taskStart > privateStart);
        String privateAdmission = admission.substring(privateStart, taskStart);
        assertTrue(privateAdmission.contains("binary owner_jiacn=binary #{ownerjiacn}"));
        for (String field : new String[]{"task_id", "task_grant_id", "task_grant_version", "assignment_revision"}) {
            assertTrue(privateAdmission.contains(field + " is null"), field);
        }
        assertFalse(privateAdmission.contains("exists"));
        String taskAdmission = admission.substring(taskStart);
        assertTrue(taskAdmission.contains("exists (select 1 from agent_task_execution_grant g"));
        assertTrue(taskAdmission.contains("binary g.executor_owner_jiacn=binary #{ownerjiacn}"));
        assertTrue(taskAdmission.contains("g.grant_version=agent_personal_workspace_execution.task_grant_version"));
        for (String field : new String[]{"tenant_id", "client_id", "owner_jiacn", "task_id", "target_agent_id"}) {
            assertTrue(taskAdmission.contains("binary g." + field + "=binary agent_personal_workspace_execution." + field), field);
        }
        assertTrue(sql.endsWith("order by created_at asc, cast(execution_id as binary) asc limit #{limit} </script>"));
    }

    private static String sql(Method method) {
        Select select = method.getAnnotation(Select.class);
        return String.join(" ", select.value()).replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }
}
