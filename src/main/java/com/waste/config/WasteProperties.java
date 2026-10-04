package com.waste.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** 计费与风控规则参数（application.yml waste.pricing.*） */
@Data
@Component
@ConfigurationProperties(prefix = "waste.pricing")
public class WasteProperties {
    private BigDecimal bagUnitPrice = new BigDecimal("8.0");
    private BigDecimal extraBagMultiplier = new BigDecimal("1.5");
    private BigDecimal avgBagWeightKg = new BigDecimal("25.0");
    private BigDecimal collectorPricePerTon = new BigDecimal("120.0");
    private BigDecimal fireLaneFine = new BigDecimal("200.0");
    private BigDecimal mixedWasteFine = new BigDecimal("100.0");
    private BigDecimal weighAnomalyThresholdPct = new BigDecimal("20.0");
    private BigDecimal capacityWarnRatio = new BigDecimal("0.9");
    private int lateToleranceMinutes = 30;
    private int compensationWindowDays = 7;   // 满溢占车位投诉定责的调查窗口（天）
}
