package com.waste.enums;

/** 账单类型：业主 / 施工队 / 物业垫付 / 清运公司结算 / 清运公司履约扣款 / 业主押金补缴 */
public enum BillType {
    OWNER, CONSTRUCTION_TEAM, PROPERTY_ADVANCE, COLLECTOR,
    COLLECTOR_DEDUCTION,  // 清运公司履约扣款（投诉赔付）
    OWNER_REPLENISH       // 业主押金补缴单（投诉赔付从押金扣除后通知补缴）
}
