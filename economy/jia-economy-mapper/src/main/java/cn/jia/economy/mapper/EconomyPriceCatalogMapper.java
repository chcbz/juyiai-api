package cn.jia.economy.mapper;

import cn.jia.economy.entity.EconomyModelPriceRow;
import cn.jia.economy.entity.EconomyToolPriceRow;
import cn.jia.economy.entity.EconomyTaskModelTariffRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Exact scoped reads. Version rows are immutable; current pointers are maintained explicitly. */
public interface EconomyPriceCatalogMapper {
    @Select("""
            SELECT p.* FROM economy_task_model_tariff p WHERE p.tenant_id=#{tenantId} AND p.client_id=#{clientId} AND p.model=#{model}
            """)
    EconomyTaskModelTariffRow findTaskTariff(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("model") String model);

    @Select("""
            SELECT p.* FROM economy_model_price p JOIN economy_model_price_current c ON p.tenant_id=c.tenant_id AND p.client_id=c.client_id AND p.channel=c.channel AND p.model=c.model AND p.tier=c.tier AND p.price_version=c.price_version WHERE p.tenant_id=#{tenantId} AND p.client_id=#{clientId} AND p.channel=#{channel} AND p.model=#{model} AND p.tier=#{tier}
            """)
    EconomyModelPriceRow findCurrentModel(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("channel") String channel,
            @Param("model") String model,
            @Param("tier") String tier);

    @Select("""
            SELECT p.* FROM economy_model_price p WHERE p.tenant_id=#{tenantId} AND p.client_id=#{clientId} AND p.channel=#{channel} AND p.model=#{model} AND p.tier=#{tier} AND p.price_version=#{priceVersion}
            """)
    EconomyModelPriceRow findModelVersion(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("channel") String channel,
            @Param("model") String model,
            @Param("tier") String tier,
            @Param("priceVersion") String priceVersion);

    @Select("""
            SELECT p.* FROM economy_tool_price p JOIN economy_tool_price_current c ON p.tenant_id=c.tenant_id AND p.client_id=c.client_id AND p.channel=c.channel AND p.tool=c.tool AND p.unit=c.unit AND p.price_version=c.price_version WHERE p.tenant_id=#{tenantId} AND p.client_id=#{clientId} AND p.channel=#{channel} AND p.tool=#{tool} AND p.unit=#{unit}
            """)
    EconomyToolPriceRow findCurrentTool(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("channel") String channel,
            @Param("tool") String tool,
            @Param("unit") String unit);

}
