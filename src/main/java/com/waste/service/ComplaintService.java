package com.waste.service;

import com.waste.auth.Session;
import com.waste.common.BizException;
import com.waste.common.NoGen;
import com.waste.common.TraceLogger;
import com.waste.config.RabbitConfig;
import com.waste.entity.Bill;
import com.waste.entity.Complaint;
import com.waste.enums.BillStatus;
import com.waste.enums.ComplaintStatus;
import com.waste.enums.ComplaintType;
import com.waste.enums.Role;
import com.waste.mq.EventBus;
import com.waste.repository.BillRepository;
import com.waste.repository.ComplaintRepository;
import com.waste.repository.WastePointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ComplaintService {

    private final ComplaintRepository complaintRepo;
    private final BillRepository billRepo;
    private final WastePointRepository wastePointRepo;
    private final EventBus eventBus;
    private final TraceLogger traceLogger;

    /** 发起投诉（异味/扬尘/消防通道/溢出/混入生活垃圾/车辆迟到/称重异常/费用争议/满溢占车位索赔） */
    @Transactional
    public Complaint create(Session user, ComplaintType type, Long appointmentId, Long registrationId,
                            Long billId, Long wastePointId, BigDecimal claimAmount, String description) {
        Complaint c = new Complaint();
        c.setComplaintNo(NoGen.of("CPL"));
        c.setComplainantId(user.userId());
        c.setComplainantName(user.name());
        c.setType(type);
        c.setTargetAppointmentId(appointmentId);
        c.setTargetRegistrationId(registrationId);
        c.setDescription(description);

        if (type == ComplaintType.FEE_DISPUTE) {
            if (billId == null) {
                throw new BizException("费用争议必须关联账单 billId");
            }
            Bill bill = billRepo.findById(billId).orElseThrow(() -> new BizException(404, "账单不存在"));
            bill.setStatus(BillStatus.DISPUTED);
            billRepo.save(bill);
            c.setBillId(billId);
        }
        if (type == ComplaintType.OVERFLOW_PARKING) {
            // 满溢占车位索赔：必须关联垃圾点并给出主张减免金额
            if (wastePointId == null) {
                throw new BizException("满溢占车位投诉必须关联垃圾点 wastePointId");
            }
            wastePointRepo.findById(wastePointId)
                    .orElseThrow(() -> new BizException(404, "垃圾点不存在"));
            if (claimAmount == null || claimAmount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("满溢占车位投诉必须填写主张减免物业费金额 claimAmount（大于 0）");
            }
            c.setWastePointId(wastePointId);
            c.setClaimAmount(claimAmount);
        }
        complaintRepo.save(c);
        traceLogger.record(appointmentId, "COMPLAINT", c.getId(), user, "COMPLAINT_CREATE",
                "投诉[" + type + "]: " + description);
        eventBus.publish(RabbitConfig.RK_COMPLAINT_CREATED, c.getId());
        return c;
    }

    /** 物业处理投诉 */
    @Transactional
    public Complaint handle(Session property, Long id, ComplaintStatus action, String resolution) {
        if (action == ComplaintStatus.OPEN) {
            throw new BizException("处理动作只能是 PROCESSING / RESOLVED / REJECTED");
        }
        Complaint c = complaintRepo.findById(id).orElseThrow(() -> new BizException(404, "投诉不存在"));
        c.setStatus(action);
        c.setHandlerId(property.userId());
        c.setHandlerName(property.name());
        c.setResolution(resolution);
        if (action == ComplaintStatus.RESOLVED || action == ComplaintStatus.REJECTED) {
            c.setResolvedAt(LocalDateTime.now());
        }
        complaintRepo.save(c);
        traceLogger.record(c.getTargetAppointmentId(), "COMPLAINT", c.getId(), property, "COMPLAINT_HANDLE",
                "投诉处理[" + action + "]: " + resolution);
        return c;
    }

    public List<Complaint> list(Session session, ComplaintStatus status) {
        if (session.role() == Role.PROPERTY || session.role() == Role.FINANCE || session.role() == Role.ADMIN) {
            return status != null ? complaintRepo.findByStatusOrderByIdDesc(status) : complaintRepo.findAll();
        }
        return complaintRepo.findByComplainantIdOrderByIdDesc(session.userId());
    }
}
