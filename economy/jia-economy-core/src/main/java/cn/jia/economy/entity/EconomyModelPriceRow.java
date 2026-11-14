package cn.jia.economy.entity;

import lombok.Data;

/** Internal price catalog row, not an HTTP contract. */
@Data
public class EconomyModelPriceRow {
    private String tenantId;
    private String clientId;
    private String channel;
    private String model;
    private String tier;
    private String priceVersion;
    private String priceDataJson;
}
