package com.waste.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 赔付说明：定责后分别面向业主、邻居（投诉人）、清运公司、物业财务生成，
 * 内容按受众裁剪——投诉人只看处理结果，财务可见扣款来源与入账去向。
 */
@Data
@Entity
@Table(name = "compensation_notice")
public class CompensationNotice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long compensationId;

    /** OWNER 业主 / NEIGHBOR 邻居(投诉人) / COLLECTOR 清运公司 / FINANCE 物业财务 */
    @Column(nullable = false, length = 16)
    private String audience;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(nullable = false, length = 2048)
    private String content;

    private LocalDateTime createdAt = LocalDateTime.now();
}
