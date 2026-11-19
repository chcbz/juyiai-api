package cn.jia.agent.api;

import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.InstalledSkillDescriptorLookup;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.chat.service.ConversationToolProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import static cn.jia.agent.position.PositionSkillException.require;

/** Role-gated private conversation tools. The model cannot provide identity, prices, URLs, or FX. */
@Service
public final class ModelConversationToolAdapter implements ConversationToolProvider {
    static final PositionSkillService.RequiredPositionSkill REQUIRED=new PositionSkillService.RequiredPositionSkill(
            cn.jia.agent.position.PositionSkillPolicy.PLATFORM_MODELS,"model-maintenance","MODEL_POSITION_REQUIRED","MODEL_SKILL_REQUIRED");
    private static final String READ_MODEL="model_read_price", ADD_MODEL="model_add_price",
            READ_HERO="model_read_hero_binding", BIND="model_set_hero_binding";
    private static final Pattern MODEL=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}");
    private static final Pattern PERSONA=Pattern.compile("[a-z0-9][a-z0-9-]{0,99}");
    private static final List<Map<String,Object>> BIND_TOOLS=List.of(
            tool(READ_HERO,"按已确认的好汉规范标识 personaCode 读取目标模型；返回配置，不是运行观测。", "personaCode"),
            tool(BIND,"用旧目标模型比较并修改指定好汉配置，目标必须已有有效托管套餐。不会切换或重启 Runtime。",
                    "personaCode","expectedModel","model"));
    private static final List<Map<String,Object>> PRICE_TOOLS=List.of(
            tool(READ_MODEL,"从平台目录读回精确模型 ID 已录入的 BaseLLM 公开参考价。", "model"),
            tool(ADD_MODEL,"从默认 BaseLLM 来源录入精确型号的输入、输出和缓存命中单价；保留两个档位，不覆盖现有版本。", "model"));

    private final PositionSkillService positions;
    private final AgentIdentityService identities;
    private final InstalledSkillDescriptorLookup installed;
    private final ModelMaintenanceOperations operations;

    public ModelConversationToolAdapter(PositionSkillService positions,AgentIdentityService identities,
            InstalledSkillDescriptorLookup installed,ModelMaintenanceOperations operations) {
        this.positions=positions;this.identities=identities;this.installed=installed;this.operations=operations;
    }
    @Override public Set<String> names(){return Set.of(READ_MODEL,ADD_MODEL,READ_HERO,BIND);}
    @Override public List<Map<String,Object>> catalog(Context ctx,Boundary boundary) {
        // Avoid touching the lock or preparing packages for ordinary conversations.
        var subject=ctx.subject();
        if(!positions.hasPositionSkill(new PositionAuthority.Scope(subject.tenantId(),subject.clientId(),subject.ownerJiacn()),subject.agentId(),REQUIRED))
            return List.of();
        try {withAuthority(ctx,boundary,false,scope->true);}
        catch(PositionSkillException denied){if(denied.status()==403)return List.of();throw denied;}
        var tools=new ArrayList<>(BIND_TOOLS);
        try {withAuthority(ctx,boundary,true,scope->true); tools.addAll(PRICE_TOOLS);}
        catch(PositionSkillException denied){if(denied.status()!=403)throw denied;}
        return List.copyOf(tools);
    }

    @Override public Object call(Context ctx,Boundary boundary,String tool,Map<String,Object> args) {
        boolean price=priceAuthority(tool);
        if (ADD_MODEL.equals(tool)) withAuthority(ctx,boundary,true,scope->true);
        var operation=prepare(tool,args);
        return withAuthority(ctx,boundary,price,operation);
    }

    static boolean transactionalWrite(String tool) {
        return switch(tool) {
            case ADD_MODEL,BIND -> true;
            case READ_MODEL,READ_HERO -> false;
            default -> throw new IllegalArgumentException("Unknown model tool");
        };
    }
    static boolean priceAuthority(String tool) {
        return switch(tool) {
            case READ_MODEL,ADD_MODEL -> true;
            case READ_HERO,BIND -> false;
            default -> throw new IllegalArgumentException("Unknown model tool");
        };
    }
    /** Performs external preparation only. Caller checks price access before fetching, then
     * re-admits inside the execution transaction before invoking the returned operation. */
    Function<PositionAuthority.Scope,Map<String,Object>> prepare(String tool,Map<String,Object> args) {
        return switch(tool) {
            case READ_MODEL -> {
                exact(args,"model");var model=arg(args,"model",MODEL);
                yield scope->operations.readPrice(scope,model);
            }
            case ADD_MODEL -> {
                exact(args,"model");yield operations.prepareAddPrice(arg(args,"model",MODEL));
            }
            case READ_HERO -> {
                exact(args,"personaCode");var persona=arg(args,"personaCode",PERSONA);
                yield scope->operations.readBinding(scope,persona);
            }
            case BIND -> {
                exact(args,"personaCode","expectedModel","model");var persona=arg(args,"personaCode",PERSONA);
                var expected=arg(args,"expectedModel",MODEL);var model=arg(args,"model",MODEL);
                yield scope->operations.changeBinding(scope,persona,expected,model);
            }
            default -> throw new IllegalArgumentException("Unknown model tool");
        };
    }

    private <T> T withAuthority(Context ctx,Boundary boundary,boolean price,Function<PositionAuthority.Scope,T> action) {
        var subject=ctx.subject();
        var scope=new PositionAuthority.Scope(subject.tenantId(),subject.clientId(),subject.ownerJiacn());
        var skillScope=new SkillCapabilityLookup.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),subject.agentId());
        // Pre-check before preparing package descriptions; re-admit under the native fence at commit.
        boundary.execute(()->admit(ctx,scope,price));
        return installed.withPreparedRead(skillScope,()->boundary.execute(()-> {
            var ids=admit(ctx,scope,price);
            boolean available=installed.readCurrent(skillScope).skills().stream().anyMatch(d->
                    d.isInstructionOn(ctx.runtimeInstallationId(),ctx.runtimeHostId())
                    && REQUIRED.skillKey().equals(d.skillKey()) && ids.contains(d.installationId()));
            require(available,403,"MODEL_SKILL_REQUIRED");
            return action.apply(scope);
        }));
    }
    List<Map<String,Object>> nativeCatalog(Context ctx,PositionAuthority.Scope scope,List<Map<String,Object>> selections) {
        if(!positions.hasPositionSkill(scope,ctx.subject().agentId(),REQUIRED))return List.of();
        var result=new ArrayList<Map<String,Object>>();
        for(boolean price:List.of(false,true))try {
            authorizeFrozen(ctx,scope,price,selections);result.addAll(price?PRICE_TOOLS:BIND_TOOLS);
        }catch(PositionSkillException denied){if(denied.status()!=403)throw denied;}
        return result;
    }
    private cn.jia.agent.mapper.skill.SkillManagementMapper managed;
    @org.springframework.beans.factory.annotation.Autowired public void setManaged(cn.jia.agent.mapper.skill.SkillManagementMapper value){managed=value;}
    void authorizeFrozen(Context ctx,PositionAuthority.Scope scope,boolean price,List<Map<String,Object>> selections) {
        var ids=admit(ctx,scope,price);
        var state=managed.lockState(scope.tenantId(),scope.clientId(),ctx.subject().agentId(),REQUIRED.skillKey());
        require(state!=null && Boolean.TRUE.equals(state.getDesiredEnabled()) && state.getPendingChangeId()==null
                && ids.contains(state.getActiveInstallationId()) && selections.stream().map(s->(Map<?,?>)s.get("skill"))
                .anyMatch(s->REQUIRED.skillKey().equals(s.get("key")) && state.getActiveInstallationId().equals(s.get("installationId"))),403,"MODEL_SKILL_REQUIRED");
    }
    private Set<String> admit(Context ctx,PositionAuthority.Scope scope,boolean price) {
        var agent=ctx.subject().agentId();
        var identity=identities.requireRegistrationIdentityInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),agent);
        require(agent.equals(identity.getCanonicalAgentId()) && identity.getBindingId()!=null,403,"MODEL_POSITION_REQUIRED");
        return positions.positionSkillInstallations(scope,agent,identity.getBindingId(),ctx.runtimeInstallationId(),ctx.runtimeHostId(),REQUIRED,price);
    }
    private static Map<String,Object> tool(String name,String description,String... fields) {
        var props=new java.util.LinkedHashMap<String,Object>();for(var field:fields)props.put(field,Map.of("type","string"));
        return Map.of("type","function","name",name,"description",description,"inputSchema",
                Map.of("type","object","properties",props,"required",List.of(fields),"additionalProperties",false));
    }
    private static void exact(Map<String,Object> args,String... fields) {
        require(args!=null && args.keySet().equals(Set.of(fields)),400,"MODEL_TOOL_ARGUMENT_INVALID");
    }
    private static String arg(Map<String,Object> args,String field,Pattern format) {
        if(args.get(field) instanceof String value && format.matcher(value).matches())return value;
        throw new PositionSkillException(400,"MODEL_TOOL_ARGUMENT_INVALID");
    }
}
