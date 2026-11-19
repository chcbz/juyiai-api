package cn.jia.agent.api;

import cn.jia.agent.mapper.hosting.PersonaModelHostingStore;
import cn.jia.agent.position.PositionSkillException;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.InstalledSkillDescriptorLookup;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.chat.service.ConversationToolProvider;
import cn.jia.economy.entity.EconomyModelPriceRow;
import cn.jia.economy.mapper.EconomyPriceCatalogMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import static cn.jia.agent.position.PositionSkillException.require;

/** Role-gated private conversation tools. The model cannot provide identity, prices, URLs, or FX. */
@Service
public final class ModelConversationToolAdapter implements ConversationToolProvider {
    static final String CHANNEL="public-api-reference", TIER="standard";
    private static final String READ_MODEL="model_read_price", ADD_MODEL="model_add_price",
            READ_HERO="model_read_hero_binding", BIND="model_set_hero_binding";
    private static final Pattern MODEL=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}");
    private static final Pattern PERSONA=Pattern.compile("[a-z0-9][a-z0-9-]{0,99}");
    private static final List<Map<String,Object>> BIND_TOOLS=List.of(
            tool(READ_HERO,"读取现有好汉目标模型。孙二娘规范标识为 sunerniang；返回配置，不是运行观测。", "personaCode"),
            tool(BIND,"用旧目标模型比较并修改指定好汉配置，目标必须已有有效托管套餐。不会切换或重启 Runtime。",
                    "personaCode","expectedModel","model"));
    private static final List<Map<String,Object>> PRICE_TOOLS=List.of(
            tool(READ_MODEL,"从平台目录读回精确模型 ID 已录入的 BaseLLM 公开参考价。", "model"),
            tool(ADD_MODEL,"从默认 BaseLLM 来源录入精确型号的输入、输出和缓存命中单价；保留两个档位，不覆盖现有版本。", "model"));

    private final PositionSkillService positions;
    private final AgentIdentityService identities;
    private final InstalledSkillDescriptorLookup installed;
    private final PersonaModelHostingStore bindings;
    private final EconomyPriceCatalogMapper prices;
    private final BaseLlmModelPriceSource source;
    private final ObjectMapper json;

    public ModelConversationToolAdapter(PositionSkillService positions,AgentIdentityService identities,
            InstalledSkillDescriptorLookup installed,PersonaModelHostingStore bindings,
            EconomyPriceCatalogMapper prices,BaseLlmModelPriceSource source,ObjectMapper json) {
        this.positions=positions;this.identities=identities;this.installed=installed;this.bindings=bindings;
        this.prices=prices;this.source=source;this.json=json;
    }
    @Override public Set<String> names(){return Set.of(READ_MODEL,ADD_MODEL,READ_HERO,BIND);}
    @Override public List<Map<String,Object>> catalog(Context ctx,Boundary boundary) {
        // Avoid touching the lock or preparing packages for ordinary conversations.
        var subject=ctx.subject();
        if(!positions.hasModelMaintenancePosition(new PositionAuthority.Scope(subject.tenantId(),subject.clientId(),subject.ownerJiacn()),subject.agentId()))
            return List.of();
        try {withAuthority(ctx,boundary,false,scope->true);}
        catch(PositionSkillException denied){if(denied.status()==403)return List.of();throw denied;}
        var tools=new ArrayList<>(BIND_TOOLS);
        try {withAuthority(ctx,boundary,true,scope->true); tools.addAll(PRICE_TOOLS);}
        catch(PositionSkillException denied){if(denied.status()!=403)throw denied;}
        return List.copyOf(tools);
    }

    @Override public Object call(Context ctx,Boundary boundary,String tool,Map<String,Object> args) {
        return switch(tool) {
            case READ_MODEL -> {
                exact(args,"model");var model=arg(args,"model",MODEL);
                yield withAuthority(ctx,boundary,true,scope-> {
                    var row=current(scope,model);return row==null?Map.of("model",model,"configured",false):priceReceipt(row,"CURRENT");
                });
            }
            case ADD_MODEL -> {
                exact(args,"model");var model=arg(args,"model",MODEL);
                withAuthority(ctx,boundary,true,scope->true);
                var quote=source.read(model); // External I/O is outside identity, conversation and DB locks.
                try {yield withAuthority(ctx,boundary,true,scope->add(scope,quote));}
                catch(DuplicateKeyException conflict){throw new PositionSkillException(409,"MODEL_PRICE_CONFLICT");}
            }
            case READ_HERO -> {
                exact(args,"personaCode");var persona=arg(args,"personaCode",PERSONA);
                yield withAuthority(ctx,boundary,false,scope->Map.of("personaCode",persona,"configuredModel",hero(scope,persona).modelId()));
            }
            case BIND -> {
                exact(args,"personaCode","expectedModel","model");var persona=arg(args,"personaCode",PERSONA);
                var expected=arg(args,"expectedModel",MODEL);var model=arg(args,"model",MODEL);
                yield withAuthority(ctx,boundary,false,scope-> {
                    if(expected.equals(model)) {
                        require(model.equals(hero(scope,persona).modelId()),409,"MODEL_BINDING_CONFLICT");
                        return Map.of("personaCode",persona,"configuredModel",model,"disposition","UNCHANGED");
                    }
                    require(bindings.changeModel(scope.tenantId(),scope.clientId(),persona,expected,model),409,"MODEL_BINDING_CONFLICT_OR_PLAN_MISSING");
                    return Map.of("personaCode",persona,"previousConfiguredModel",expected,"configuredModel",model,"disposition","CHANGED");
                });
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
            boolean available=installed.readCurrent(skillScope).skills().stream().anyMatch(d->d.verifiedNative()
                    && d.manifestSha256()==null && "model-maintenance".equals(d.skillKey()) && ids.contains(d.installationId())
                    && Objects.equals(ctx.runtimeInstallationId(),d.runtimeInstallationId()) && Objects.equals(ctx.runtimeHostId(),d.runtimeHostId()));
            require(available,403,"MODEL_SKILL_REQUIRED");
            return action.apply(scope);
        }));
    }
    private Set<String> admit(Context ctx,PositionAuthority.Scope scope,boolean price) {
        var agent=ctx.subject().agentId();
        var identity=identities.requireRegistrationIdentityInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),agent);
        require(agent.equals(identity.getCanonicalAgentId()) && identity.getBindingId()!=null,403,"MODEL_POSITION_REQUIRED");
        return positions.modelMaintenanceInstallations(scope,agent,identity.getBindingId(),ctx.runtimeInstallationId(),ctx.runtimeHostId(),price);
    }
    private PersonaModelHostingStore.Tariff hero(PositionAuthority.Scope scope,String persona) {
        var row=bindings.find(scope.tenantId(),scope.clientId(),persona);
        require(row!=null,404,"MODEL_HERO_NOT_FOUND");return row;
    }
    private EconomyModelPriceRow current(PositionAuthority.Scope scope,String model) {
        return prices.findCurrentModel(scope.tenantId(),scope.clientId(),CHANNEL,model,TIER);
    }
    private Map<String,Object> add(PositionAuthority.Scope scope,BaseLlmModelPriceSource.Quote quote) {
        var old=current(scope,quote.model());
        if(old!=null) {
            require(quote.version().equals(old.getPriceVersion()),409,"MODEL_PRICE_ALREADY_EXISTS");
            return priceReceipt(old,"UNCHANGED");
        }
        var row=new EconomyModelPriceRow();row.setTenantId(scope.tenantId());row.setClientId(scope.clientId());
        row.setChannel(CHANNEL);row.setTier(TIER);row.setModel(quote.model());row.setPriceVersion(quote.version());
        row.setCreatedAt(System.currentTimeMillis());row.setPriceDataJson(json.writeValueAsString(quote.priceData()));
        require(prices.insertModelVersion(row)==1 && prices.insertCurrentModel(row)==1,409,"MODEL_PRICE_CONFLICT");
        return priceReceipt(row,"CREATED");
    }
    private Map<String,Object> priceReceipt(EconomyModelPriceRow row,String disposition) {
        var data=json.readTree(row.getPriceDataJson());
        return Map.of("model",row.getModel(),"configured",true,"channel",row.getChannel(),"tier",row.getTier(),
                "priceVersion",row.getPriceVersion(),"price",data,"disposition",disposition);
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
