package com.waste.enums;

/** 赔付责任分摊单状态 */
public enum CompensationStatus {
    DETERMINED, // 已定责并完成扣款/入账
    REJECTED    // 定责不成立（无赔付）
}
