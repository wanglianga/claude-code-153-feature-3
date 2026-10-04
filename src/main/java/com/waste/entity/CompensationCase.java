package com.waste.entity;

import com.waste.enums.LiableParty;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 投诉赔付责任分摊单：满溢占车位投诉定责后生成。
 * 串联预约记录、清运迟到、物业审核容量、施工队实际堆放与投诉时间形成证据链，
 * 确定责任方后执行赔付（清运公司履约扣款 / 业主押金扣除并通知补缴 / 物业承担），
 * 并生成给业主、邻居（投诉人）、清运公司、物业财务的四方说明。
 */
@Data
@Entity
@Table(name = "compensation_case")
public class CompensationCase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String caseNo;

    @Column(nullable = false)
    private Long complaintId;

    @Column(nullable = false, length = 32)
    private String complaintNo;

    @Column(nullable = false)
    private Long wastePointId;

    @Column(nullable = false, length = 64)
    private String wastePointName;

    @Column(nullable = false)
    private Long complainantId;

    @Column(nullable = false, length = 64)
    private String complainantName;

    /** 投诉人主张的减免金额 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal claimAmount;

    /** 物业核定赔付金额 */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal compensationAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LiableParty liableParty;

    /** true=系统按证据链自动判定，false=物业人工指定 */
    @Column(nullable = false)
    private Boolean autoDetermined = true;

    @Column(nullable = false, length = 512)
    private String liabilityReason;

    /** 证据链（JSON）：预约记录、清运迟到、物业审核容量、施工队实际堆放、投诉时间 */
    @Column(columnDefinition = "TEXT")
    private String evidence;

    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;

    /** 扣款来源（财务视角），如：清运公司履约扣款 / 业主装修押金 / 物业运营成本 */
    @Column(nullable = false, length = 256)
    private String deductionSource;

    /** 入账去向（财务视角），如：投诉人 XXX 物业费减免 */
    @Column(nullable = false, length = 256)
    private String creditTarget;

    private Long deductionBillId;       // 履约扣款单 / 押金补缴单 / 物业垫付单
    private Long depositDeductionId;    // 押金流水（业主责任时）

    private Long registrationId;        // 责任装修登记（业主责任时）
    private Long ownerId;
    @Column(length = 64)
    private String ownerName;
    @Column(length = 64)
    private String collectorName;       // 责任清运公司（清运责任时）

    // ---- 四方说明 ----
    @Column(length = 1024)
    private String ownerStatement;      // 给业主
    @Column(length = 1024)
    private String neighborStatement;   // 给邻居/投诉人
    @Column(length = 1024)
    private String collectorStatement;  // 给清运公司
    @Column(length = 1024)
    private String financeStatement;    // 给物业财务

    @Column(nullable = false, length = 16)
    private String status = "SETTLED";

    private Long handlerId;
    @Column(length = 64)
    private String handlerName;

    private LocalDateTime createdAt = LocalDateTime.now();
}
