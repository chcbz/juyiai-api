package cn.jia.economy.entity;

import lombok.Data;

/** Internal price catalog row, not an HTTP contract. */
@Data
public class EconomyTaskModelTariffRow {
    private String tenantId;
    private String clientId;
    private String model;
    private String channel;
    private String tier;
    private String toolsJson;
}
