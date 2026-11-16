package cn.jia.agent.service.impl;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Retired contract absence; native execution and persistent fences are tested in SkillExecutionServiceTest. */
class PersonalWorkspaceControlledImageV3StartTest {
 @Test void retiredProviderStartTypesAreNotPartOfTheExecutionContract() {
  var names=Arrays.stream(PersonalWorkspaceExecutionService.class.getDeclaredClasses())
    .map(Class::getSimpleName).collect(java.util.stream.Collectors.toSet());
  assertFalse(names.contains("ControlledProviderStartV3"));
  assertFalse(names.contains("ControlledProviderStartReceiptV3"));
 }

}
