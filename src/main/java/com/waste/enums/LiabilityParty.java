package com.waste.enums;

/** 投诉赔付主要责任方：清运公司（清运延误）/ 业主（超量未申报） */
public enum LiabilityParty {
    COLLECTOR,  // 清运公司：清运延误导致满溢
    OWNER       // 业主：超量未申报导致满溢
}
