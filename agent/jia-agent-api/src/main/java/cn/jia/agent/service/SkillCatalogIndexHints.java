package cn.jia.agent.service;

import java.util.List;

/** Local directory locators only. Not an execution or disclosure grant. */
public interface SkillCatalogIndexHints {
    record Key(String tenant,String client,String agent) { }
    List<Key> local(String tenant,String optionalClient);
}
