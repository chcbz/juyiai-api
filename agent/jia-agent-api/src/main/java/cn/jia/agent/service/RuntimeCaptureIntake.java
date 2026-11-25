package cn.jia.agent.service;

import cn.jia.core.entity.JsonRequest;
import cn.jia.economy.usage.UsageCaptureJournal;
import java.util.Map;
import java.util.function.Function;

/** Internal V2 delivery contract shared by original workspace and discussion sources, not a wallet API. */
public interface RuntimeCaptureIntake {
    interface Fence { UsageCaptureJournal.Result apply(Function<UsageCaptureJournal.Binding,UsageCaptureJournal.Result> operation); }
    interface PlanCheck { boolean matches(UsageCaptureJournal.Binding binding,String snapshot,Map<?,?> observation); }
    record Reply(int status,String code,String ackJson) {}
    Reply submit(String execution,String run,JsonRequest<Map<String,Object>> request,String key,Fence fence,PlanCheck plan);
    Reply receipt(Fence fence,String id);
    Reply current(Fence fence,String id);
}
