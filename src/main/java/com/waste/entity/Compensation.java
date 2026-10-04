package com.waste.entity;

import com.waste.enums.CompensationStatus;
import com.waste.enums.LiabilityParty;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 投诉赔付责任分摊单。
 * 串联预约记录、清运公司迟到、物业容量审核、施工队实际堆放与投诉时间后定责：
 * COLLECTOR → 赔付计入清运公司履约扣款；OWNER → 从装修押金扣除并通知业主补缴。
 */
@Data
@Entity
@Table(name = "compensation")
public class Compensation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String compNo;

    @Column(nullable = false)
    private Long complaintId;

    @Column(nullable = false)
    private Long complainantId;

    @Column(nullable = false, length = 64)
    private String complainantName;

    private Long wastePointId;

    @Column(length = 64)
    private String wastePointName;

    private Long appointmentId;

    @Column(length = 32)
    private String apptNo;

    private Long registrationId;
    private Long reportId;
    private Long dispatchId;

    @Column(length = 32)
    private String dispatchNo;

    /** 责任清运公司（COLLECTOR 责任时） */
    @Column(length = 64)
    private String companyName;

    /** 责任业主（OWNER 责任时） */
    private Long ownerId;

    @Column(length = 64)
    private String ownerName;

    @Column(length = 64)
    private String constructionTeam;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LiabilityParty liableParty;

    /** 赔付金额（= 给予邻居的物业费减免） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal compensationAmount;

    /** 清运公司履约扣款金额（COLLECTOR 责任时等于赔付金额，否则 0） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal collectorPenaltyAmount = BigDecimal.ZERO;

    /** 业主押金扣除金额（OWNER 责任时等于赔付金额，否则 0） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal depositDeductedAmount = BigDecimal.ZERO;

    /** 业主应补缴押金金额（OWNER 责任时挂账） */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal topUpDue = BigDecimal.ZERO;

    /** 物业财务赔付入账账单（COMPENSATION 类型）ID */
    private Long compensationBillId;

    /** 清运公司履约扣款账单（COLLECTOR 类型负数冲减）ID */
    private Long collectorDeductionBillId;

    /** 业主押金扣款流水 ID */
    private Long depositDeductionId;

    /** 业主补缴挂账账单（OWNER 类型）ID */
    private Long topUpBillId;

    /** 押金不足赔付时的物业垫付账单 ID */
    private Long propertyAdvanceBillId;

    /** 定责结论摘要 */
    @Column(nullable = false, length = 512)
    private String reasonSummary;

    /** 证据链串联明细（预约/迟到/容量审核/施工堆放/投诉时间） */
    @Column(nullable = false, length = 2048)
    private String evidenceSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CompensationStatus status = CompensationStatus.DETERMINED;

    private Long decidedById;

    @Column(length = 64)
    private String decidedByName;

    private LocalDateTime decidedAt;
    private LocalDateTime createdAt = LocalDateTime.now();
}
