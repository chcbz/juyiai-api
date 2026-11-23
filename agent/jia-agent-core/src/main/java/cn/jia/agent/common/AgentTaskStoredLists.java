package cn.jia.agent.common;

import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import java.util.*;
/** Existing task-row list encoding; used by public projections and quote observation without reading a plan twice. */
public final class AgentTaskStoredLists {
    private AgentTaskStoredLists(){}
    public static List<String> read(String json) {
        if (StringUtil.isBlank(json) || "[]".equals(json.trim())) {
            return Collections.emptyList();
        }
        List<String> parsed = JsonUtil.jsonToList(json, String.class);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        return Arrays.stream(json.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

}
