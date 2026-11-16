package cn.jia.agent.service.impl;

import cn.jia.agent.api.PersonalWorkspaceConversationRuntimeController;
import cn.jia.agent.api.PersonalWorkspaceExecutionController;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.SkillExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Retirement assertions replace the old v3 positive wire fixtures; no v3 execution is supported. */
class PersonalWorkspaceControlledImageV3ExecutionTest {
    @Test void nativeInboxHasExactlyOneCommandContract() {
        assertArrayEquals(new Class<?>[]{SkillExecutionService.Command.class},
                PersonalWorkspaceExecutionService.ConversationCommandView.class.getPermittedSubclasses());
    }

    @Test void retiredV3RuntimeControllerDoesNotExposeInputOrStart() {
        var names = Arrays.stream(PersonalWorkspaceConversationRuntimeController.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
        assertFalse(names.contains("inputsV3"));
        assertFalse(names.contains("controlledProviderStartV3"));
        assertTrue(names.containsAll(Set.of("claim", "renew", "inputs")));
    }

    @Test void historicalOwnerControllerDoesNotExposeExecutionWrites() {
        var methods = PersonalWorkspaceExecutionController.class.getDeclaredMethods();
        assertTrue(Arrays.stream(methods).noneMatch(method -> method.isAnnotationPresent(PostMapping.class)));
        var names = Arrays.stream(methods).map(java.lang.reflect.Method::getName).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of("get", "list", "request")));
        assertFalse(names.contains("capabilities"));
    }
}
