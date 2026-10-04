package com.waste.entity;

import com.waste.enums.ComplaintStatus;
import com.waste.enums.ComplaintType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 投诉（异味/扬尘/占用消防通道/溢出/混入生活垃圾/车辆迟到/称重异常/费用争议） */
@Data
@Entity
@Table(name = "complaint")
public class Complaint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 32)
    private String complaintNo;

    @Column(nullable = false)
    private Long complainantId;

    @Column(nullable = false, length = 64)
    private String complainantName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ComplaintType type;

    private Long targetAppointmentId;   // 关联预约（可空）
    private Long targetRegistrationId;  // 关联登记（可空）
    private Long billId;                // 费用争议关联账单（可空）
    private Long wastePointId;          // 满溢/占用投诉关联垃圾点（可空）

    /** 投诉人诉求：减免物业费金额（元，可空） */
    private java.math.BigDecimal feeWaiverAmount;

    /** 定责后生成的赔付责任分摊单 ID（可空） */
    private Long compensationId;

    @Column(nullable = false, length = 512)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ComplaintStatus status = ComplaintStatus.OPEN;

    private Long handlerId;

    @Column(length = 64)
    private String handlerName;

    @Column(length = 512)
    private String resolution;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime resolvedAt;
}
