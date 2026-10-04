package com.waste.enums;

/** 投诉类型 */
public enum ComplaintType {
    ODOR,           // 异味
    DUST,           // 扬尘
    FIRE_LANE,      // 占用消防通道
    OVERFLOW,       // 垃圾点溢出
    MIXED_WASTE,    // 混入生活垃圾
    LATE_VEHICLE,   // 车辆迟到
    WEIGH_ANOMALY,  // 称重数据异常
    FEE_DISPUTE,    // 费用争议
    OVERFLOW_PARKING, // 垃圾点满溢占用车位（要求减免物业费，需定责赔付）
    OTHER
}
