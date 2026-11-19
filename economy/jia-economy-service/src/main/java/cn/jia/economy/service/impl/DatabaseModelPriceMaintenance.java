package cn.jia.economy.service.impl;

import cn.jia.economy.entity.EconomyModelPriceRow;
import cn.jia.economy.mapper.EconomyPriceCatalogMapper;
import cn.jia.economy.pricing.ModelPriceCatalogMaintenance;
import cn.jia.economy.service.EconomyScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

@Service
public class DatabaseModelPriceMaintenance implements ModelPriceCatalogMaintenance {
    private final EconomyPriceCatalogMapper mapper;
    private final ObjectMapper json;
    public DatabaseModelPriceMaintenance(EconomyPriceCatalogMapper mapper,ObjectMapper json){this.mapper=mapper;this.json=json;}

    @Override public Optional<Price> find(EconomyScope scope,String channel,String model,String tier) {
        return Optional.ofNullable(mapper.findCurrentModel(scope.tenantId(),scope.clientId(),channel,model,tier)).map(r->view(r,"CURRENT"));
    }

    /** Insert-only. A collision rolls back version and pointer together; never overwrites either. */
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Price create(EconomyScope scope,Price candidate){
        var row=mapper.findCurrentModel(scope.tenantId(),scope.clientId(),candidate.channel(),candidate.model(),candidate.tier());
        if(row!=null) {
            if(!candidate.version().equals(row.getPriceVersion()))throw new Conflict();
            return view(row,"UNCHANGED");
        }
        row=new EconomyModelPriceRow();row.setTenantId(scope.tenantId());row.setClientId(scope.clientId());
        row.setChannel(candidate.channel());row.setTier(candidate.tier());row.setModel(candidate.model());row.setPriceVersion(candidate.version());
        row.setCreatedAt(System.currentTimeMillis());row.setPriceDataJson(json.writeValueAsString(candidate.data()));
        try {
            if(mapper.insertModelVersion(row)!=1 || mapper.insertCurrentModel(row)!=1)throw new Conflict();
        } catch(DuplicateKeyException duplicate){throw new Conflict();}
        return view(row,"CREATED");
    }
    private Price view(EconomyModelPriceRow row,String disposition) {
        Map<String,Object> data=json.readValue(row.getPriceDataJson(),new TypeReference<>(){});
        return new Price(row.getChannel(),row.getModel(),row.getTier(),row.getPriceVersion(),data,disposition);
    }
}
