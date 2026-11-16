package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlledImageConversationStartTest {
    @Test void retiredConversationAdmissionCannotWriteOrConsumeAuthority() {
        var rows=mock(PersonalWorkspaceExecutionDao.class);
        var workspace=mock(PersonalWorkspaceDao.class);
        var links=mock(PersonalWorkspaceTaskLinkDao.class);
        var runtimes=mock(AgentRuntimeDao.class);
        var storage=mock(PersonalWorkspaceStorage.class);
        var writes=mock(PersonalWorkspaceWriteService.class);
        var service=new PersonalWorkspaceExecutionServiceImpl(rows,workspace,links,runtimes,storage,writes,
                new PersonalWorkspaceExecutionProperties(List.of("image/png")));
        clearInvocations(storage); // Constructor reads the configured storage limit.
        assertFalse(java.util.Arrays.stream(PersonalWorkspaceExecutionService.class.getMethods())
                .anyMatch(method -> method.getName().equals("createConversation")));
        verifyNoInteractions(rows,workspace,links,runtimes,storage,writes);
    }
}
