package com.waste.enums;

/** 投诉赔付责任方 */
public enum LiableParty {
    COLLECTOR,  // 清运公司（清运延误导致满溢）
    OWNER,      // 业主（超量堆放未申报）
    PROPERTY    // 物业（容量审核/管理责任）
}
