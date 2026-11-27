package cn.jia.economy.service.impl;

import cn.jia.core.util.CanonicalValueEncoding;
import cn.jia.economy.mapper.CostRouteBindingMapper;
import cn.jia.economy.pricing.*;
import cn.jia.economy.pricing.CostPricingRoute;
import cn.jia.economy.pricing.CostPricingRoute.*;
import cn.jia.economy.service.EconomyScope;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** No local files, owner scope, model-name fallback, provider I/O or inferred zero rates. */
@Service
public class DatabaseCostRouteBindings implements CostRouteBindings {
    private final CostRouteBindingMapper mapper;
    private final ObjectMapper json;
    public DatabaseCostRouteBindings(CostRouteBindingMapper mapper,ObjectMapper json){this.mapper=mapper;this.json=json;}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Cost route transaction required");}
    private static void scope(EconomyScope scope){Objects.requireNonNull(scope);for(var s:List.of(scope.tenantId(),scope.clientId()))id(s,50);}
    private static void id(String s,int max){if(s==null || s.isBlank() || !s.equals(s.strip()) || s.codePointCount(0,s.length())>max)throw new IllegalArgumentException("Invalid cost route field");CanonicalValueEncoding.utf8(s);}
    private static Map<String,Object> route(CostPricingRoute route){return route.canonical();}
    private static void keys(Map<String,Object> m,String... keys){if(!m.keySet().equals(Set.of(keys)))throw new IllegalArgumentException("Invalid cost route fields");}
    private static String text(Map<String,Object> m,String key){if(!(m.get(key) instanceof String s))throw new IllegalArgumentException("Invalid cost route field");return s;}
    private static CostPricingRoute decodeRoute(Map<String,Object> m){return CostPricingRoute.fromCanonical(m);}
    private static String sha(CostRouteReference ref,long revision,CostPricingRoute route){
        var m=new HashMap<String,Object>();m.put("reference",ref.canonical());m.put("revision",Long.toString(revision));m.put("route",route==null?null:route(route));
        return CanonicalValueEncoding.digest(m);
    }
    private Map<String,Object> map(String raw){if(raw==null || raw.length()>16384)throw new IllegalArgumentException("Invalid stored route size");return json.readValue(raw,new TypeReference<Map<String,Object>>(){});}
    private Binding decode(EconomyScope scope,CostRouteBindingMapper.Row row){
        try {
            if(!scope.tenantId().equals(row.tenantId()) || !scope.clientId().equals(row.clientId()) || row.revision()<1)throw new IllegalArgumentException();
            var m=map(row.referenceJson());keys(m,"kind","component","namespace","modelOrTool","normalizationBasis");
            var ref=new CostRouteReference(text(m,"kind"),text(m,"component"),text(m,"namespace"),text(m,"modelOrTool"),text(m,"normalizationBasis"));
            var route=row.routeJson()==null?null:decodeRoute(map(row.routeJson()));
            if(route!=null){route(route);basis(ref,route);}
            if(!ref.sha().equals(row.referenceSha()) || !sha(ref,row.revision(),route).equals(row.bindingSha()))throw new IllegalArgumentException();
            return new Binding(ref,row.revision(),row.bindingSha(),route);
        }catch(RuntimeException corrupt){throw new Corrupt();}
    }
    private static void basis(CostRouteReference ref,CostPricingRoute route){if(route instanceof TokenRoute r && !ref.normalizationBasis().equals(r.normalizationBasis()))throw new IllegalArgumentException("Cost route basis mismatch");}
    @Override public Map<CostRouteReference,Binding> read(EconomyScope scope,List<CostRouteReference> references,boolean lock){
        scope(scope);if(lock)transaction();
        var wanted=new TreeMap<String,CostRouteReference>();for(var ref:references){var old=wanted.put(ref.sha(),ref);if(old!=null&&!old.equals(ref))throw new IllegalArgumentException("Cost route identity conflict");}
        var keys=List.copyOf(wanted.keySet());var result=new HashMap<CostRouteReference,Binding>();var seen=new HashSet<String>();
        for(int i=0;i<keys.size();i+=128){var batch=keys.subList(i,Math.min(i+128,keys.size()));for(var row:mapper.read(scope.tenantId(),scope.clientId(),batch,lock)){
            var b=decode(scope,row);if(!batch.contains(row.referenceSha()) || !b.reference().equals(wanted.get(row.referenceSha())) || !seen.add(row.referenceSha()))throw new Corrupt();
            if(b.route()!=null)result.put(b.reference(),b);
        }}return Map.copyOf(result);
    }
    @Override public Optional<Binding> inspect(EconomyScope scope,CostRouteReference reference){
        scope(scope);Objects.requireNonNull(reference);
        var rows=mapper.read(scope.tenantId(),scope.clientId(),List.of(reference.sha()),false);
        if(rows.isEmpty())return Optional.empty();
        if(rows.size()!=1)throw new Corrupt();
        var binding=decode(scope,rows.getFirst());
        if(!binding.reference().equals(reference))throw new Corrupt();
        return Optional.of(binding);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Binding publish(EconomyScope scope,CostRouteReference reference,long expectedRevision,CostPricingRoute route){Objects.requireNonNull(route);route(route);basis(reference,route);return change(scope,reference,expectedRevision,route);}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public long withdraw(EconomyScope scope,CostRouteReference reference,long expectedRevision){return change(scope,reference,expectedRevision,null).revision();}
    private Binding change(EconomyScope scope,CostRouteReference reference,long expected,CostPricingRoute target){
        scope(scope);transaction();if(expected<0 || expected==Long.MAX_VALUE)throw new IllegalArgumentException("Invalid route revision");
        Long current=mapper.lockHead(scope.tenantId(),scope.clientId(),reference.sha());
        if(current!=null){
            var rows=mapper.read(scope.tenantId(),scope.clientId(),List.of(reference.sha()),true);
            if(rows.size()!=1)throw new Corrupt();var old=decode(scope,rows.getFirst());
            if(!old.reference().equals(reference)||old.revision()!=current)throw new Corrupt();
            if((current==expected || current==expected+1)&&Objects.equals(old.route(),target))return old;
            if(current!=expected)throw new Conflict();
        }else if(expected!=0 || target==null)throw new Conflict();
        long revision=expected+1;var hash=sha(reference,revision,target);
        var row=new CostRouteBindingMapper.Row(scope.tenantId(),scope.clientId(),reference.sha(),revision,json.writeValueAsString(reference.canonical()),target==null?null:json.writeValueAsString(route(target)),hash);
        try {
            if(mapper.insertRevision(row)!=1 || (current==null?mapper.insertHead(row):mapper.advance(row,expected))!=1)throw new Conflict();
        }catch(DuplicateKeyException duplicate){throw new Conflict();}
        return new Binding(reference,revision,hash,target);
    }
}
