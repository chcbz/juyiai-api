package cn.jia.economy.entity;

import lombok.Data;

/** Internal price catalog row, not an HTTP contract. */
@Data
public class EconomyToolPriceRow {
    private String tenantId;
    private String clientId;
    private String channel;
    private String tool;
    private String unit;
    private String priceVersion;
    private String priceDataJson;
}
