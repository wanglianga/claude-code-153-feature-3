package com.waste.service;

import com.waste.auth.Session;
import com.waste.common.BizException;
import com.waste.common.NoGen;
import com.waste.common.TraceLogger;
import com.waste.config.WasteProperties;
import com.waste.entity.*;
import com.waste.enums.*;
import com.waste.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 投诉赔付与责任分摊。
 *
 * 定责前串联五类证据：预约记录、清运公司迟到、物业审核容量、施工队实际堆放、投诉时间，
 * 还原满溢与车位被占用的形成过程并给出建议责任方：
 * <ul>
 *   <li>主要原因为清运延误（派单迟到）→ 赔付计入清运公司履约扣款；</li>
 *   <li>主要原因为业主超量未申报（审核即超容量且存在临时加袋）→ 从装修押金扣除并通知业主补缴。</li>
 * </ul>
 * 赔付统一入账为物业财务赔付资金，用于邻居（投诉人）物业费减免；
 * 定责后生成面向业主、邻居、清运公司、物业财务的四份说明，内容按受众裁剪。
 */
@Service
@RequiredArgsConstructor
public class CompensationService {

    private final ComplaintRepository complaintRepo;
    private final CollectionAppointmentRepository apptRepo;
    private final DailyReportRepository reportRepo;
    private final RenovationRegistrationRepository regRepo;
    private final DispatchOrderRepository dispatchRepo;
    private final WastePointRepository wastePointRepo;
    private final BillRepository billRepo;
    private final DepositDeductionRepository deductionRepo;
    private final CompensationRepository compensationRepo;
    private final CompensationNoticeRepository noticeRepo;
    private final BillingService billingService;
    private final WasteProperties props;
    private final TraceLogger traceLogger;

    /** 串联证据，返回时间线与建议责任方（物业定责参考，可在定责时改判） */
    @Transactional(readOnly = true)
    public Map<String, Object> investigate(Long complaintId) {
        Evidence e = loadEvidence(complaintId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("complaintId", e.complaint.getId());
        result.put("complaintNo", e.complaint.getComplaintNo());
        result.put("type", e.complaint.getType());
        result.put("complainedAt", e.complaint.getCreatedAt());
        result.put("complainant", e.complaint.getComplainantName());
        result.put("feeWaiverRequested", e.complaint.getFeeWaiverAmount());
        result.put("description", e.complaint.getDescription());

        // 预约记录
        if (e.appt != null) {
            Map<String, Object> appt = new LinkedHashMap<>();
            appt.put("appointmentId", e.appt.getId());
            appt.put("apptNo", e.appt.getApptNo());
            appt.put("apptDate", e.appt.getApptDate());
            appt.put("timeSlot", e.appt.getTimeSlot());
            appt.put("status", e.appt.getStatus());
            appt.put("reviewer", e.appt.getReviewerName());
            appt.put("reviewedAt", e.appt.getReviewedAt());
            appt.put("capacityMessage", e.appt.getCapacityMessage());
            result.put("appointment", appt);
        }
        // 清运公司派单 / 迟到
        if (e.dispatch != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("dispatchId", e.dispatch.getId());
            d.put("dispatchNo", e.dispatch.getDispatchNo());
            d.put("companyName", e.dispatch.getCompanyName());
            d.put("plateNo", e.dispatch.getPlateNo());
            d.put("driverName", e.dispatch.getDriverName());
            d.put("plannedArrival", e.dispatch.getPlannedArrival());
            d.put("actualArrival", e.dispatch.getActualArrival());
            d.put("lateFlag", e.dispatch.getLateFlag());
            d.put("status", e.dispatch.getStatus());
            result.put("dispatch", d);
        }
        // 物业审核容量
        if (e.point != null) {
            Map<String, Object> cap = new LinkedHashMap<>();
            cap.put("wastePointId", e.point.getId());
            cap.put("name", e.point.getName());
            cap.put("capacityBags", e.point.getCapacityBags());
            cap.put("effectiveCapacity", e.point.effectiveCapacity());
            cap.put("currentBags", e.point.getCurrentBags());
            cap.put("overflowAtReview", e.overflowAtReview);
            cap.put("tempFence", e.point.getTempFence());
            result.put("capacityReview", cap);
        }
        // 施工队实际堆放
        if (e.report != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("reportId", e.report.getId());
            r.put("reportDate", e.report.getReportDate());
            r.put("bagCount", e.report.getBagCount());
            r.put("extraBagCount", e.report.getExtraBagCount());
            r.put("declaredBags", e.report.getBagCount() - e.report.getExtraBagCount());
            r.put("stackingLocation", e.report.getStackingLocation());
            r.put("submitter", e.report.getSubmitterName());
            r.put("ownerExtraUndeclared", e.ownerExtra);
            result.put("stacking", r);
        }
        if (e.reg != null) {
            Map<String, Object> reg = new LinkedHashMap<>();
            reg.put("registrationId", e.reg.getId());
            reg.put("ownerName", e.reg.getOwnerName());
            reg.put("building", e.reg.getBuilding());
            reg.put("roomNumber", e.reg.getRoomNumber());
            reg.put("constructionTeam", e.reg.getConstructionTeam());
            reg.put("depositBalance", e.reg.getDepositBalance());
            result.put("registration", reg);
        }

        result.put("late", e.late);
        result.put("alreadyCollectedAtComplaint", e.discharged);
        result.put("suggestedLiableParty", e.suggested == null ? null : e.suggested.name());
        result.put("suggestion", e.suggestion);
        result.put("timeline", e.timeline);
        result.put("defaultCompensationAmount",
                e.complaint.getFeeWaiverAmount() != null ? e.complaint.getFeeWaiverAmount()
                        : props.getOverflowCompensationDefault());
        return result;
    }

    /** 物业定责并执行赔付分摊（party 为空时采用证据建议责任方） */
    @Transactional
    public Compensation adjudicate(Session property, Long complaintId, LiabilityParty party,
                                   BigDecimal amount, String remark) {
        Evidence e = loadEvidence(complaintId);
        if (e.reg == null) {
            throw new BizException("该投诉缺少关联装修登记，无法串联证据定责");
        }
        if (compensationRepo.findByComplaintId(complaintId).isPresent()) {
            throw new BizException("该投诉已完成赔付定责，不能重复操作");
        }
        LiabilityParty liable = party != null ? party : e.suggested;
        if (liable == null) {
            throw new BizException("证据不足以自动判定责任方，请物业人工指定 COLLECTOR 或 OWNER");
        }
        BigDecimal comp = (amount != null ? amount
                : (e.complaint.getFeeWaiverAmount() != null
                        ? e.complaint.getFeeWaiverAmount()
                        : props.getOverflowCompensationDefault()))
                .setScale(2, RoundingMode.HALF_UP);
        if (comp.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("赔付金额必须大于 0");
        }
        String period = java.time.LocalDate.now().toString().substring(0, 7);

        Compensation c = new Compensation();
        c.setCompNo(NoGen.of("CMP"));
        c.setComplaintId(complaintId);
        c.setComplainantId(e.complaint.getComplainantId());
        c.setComplainantName(e.complaint.getComplainantName());
        c.setRegistrationId(e.reg.getId());
        c.setOwnerId(e.reg.getOwnerId());
        c.setOwnerName(e.reg.getOwnerName());
        c.setConstructionTeam(e.reg.getConstructionTeam());
        if (e.point != null) {
            c.setWastePointId(e.point.getId());
            c.setWastePointName(e.point.getName());
        }
        if (e.appt != null) {
            c.setAppointmentId(e.appt.getId());
            c.setApptNo(e.appt.getApptNo());
        }
        if (e.report != null) {
            c.setReportId(e.report.getId());
        }
        if (e.dispatch != null) {
            c.setDispatchId(e.dispatch.getId());
            c.setDispatchNo(e.dispatch.getDispatchNo());
            c.setCompanyName(e.dispatch.getCompanyName());
        }
        c.setLiableParty(liable);
        c.setCompensationAmount(comp);
        c.setEvidenceSummary(e.timeline);
        c.setDecidedById(property.userId());
        c.setDecidedByName(property.name());
        c.setDecidedAt(LocalDateTime.now());

        // ---- 赔付入账：扣款来源统一计入物业财务赔付资金，用于邻居物业费减免 ----
        Bill compBill = new Bill();
        compBill.setBillNo(NoGen.of("BILL"));
        compBill.setBillType(BillType.COMPENSATION);
        compBill.setPartyId(e.complaint.getComplainantId());
        compBill.setPartyName(e.complaint.getComplainantName());
        compBill.setRegistrationId(e.reg.getId());
        compBill.setAppointmentId(e.appt == null ? null : e.appt.getId());
        compBill.setPeriod(period);
        compBill.setAmount(comp);
        compBill.setStatus(BillStatus.PAID);
        compBill.setPaidAmount(comp);
        compBill.setPaidAt(LocalDateTime.now());
        String fundSource;

        if (liable == LiabilityParty.COLLECTOR) {
            fundSource = "清运公司履约扣款";
            c.setCollectorPenaltyAmount(comp);
            // 清运公司履约扣款单（负数冲减其应结算款）
            Bill penalty = new Bill();
            penalty.setBillNo(NoGen.of("BILL"));
            penalty.setBillType(BillType.COLLECTOR);
            penalty.setPartyName(e.dispatch != null ? e.dispatch.getCompanyName() : "清运公司");
            penalty.setRegistrationId(e.reg.getId());
            penalty.setAppointmentId(e.appt == null ? null : e.appt.getId());
            penalty.setPeriod(period);
            penalty.setAmount(comp.negate());
            penalty.setPaidAmount(comp.negate());
            penalty.setStatus(BillStatus.PAID);
            penalty.setPaidAt(LocalDateTime.now());
            penalty.setDetail("满溢投诉[" + e.complaint.getComplaintNo() + "]清运延误履约扣款，赔付 "
                    + comp + " 元用于邻居物业费减免（派单 "
                    + (e.dispatch != null ? e.dispatch.getDispatchNo() : "-") + " 计划 "
                    + (e.dispatch != null ? e.dispatch.getPlannedArrival() : "-") + " 实际 "
                    + (e.dispatch != null ? e.dispatch.getActualArrival() : "-") + "）");
            billRepo.save(penalty);
            c.setCollectorDeductionBillId(penalty.getId());

            compBill.setDetail("资金来源：清运公司[" + (e.dispatch != null ? e.dispatch.getCompanyName() : "-")
                    + "]履约扣款 " + comp + " 元（扣款单 " + penalty.getBillNo()
                    + "）；入账去向：赔付给投诉人 " + e.complaint.getComplainantName()
                    + "，用于减免物业费。");
            c.setReasonSummary("主要原因为清运延误：派单"
                    + (e.dispatch != null && Boolean.TRUE.equals(e.dispatch.getLateFlag()) ? "迟到" : "未按时清运")
                    + "导致垃圾点满溢、车位被占用，赔付 " + comp + " 元计入清运公司履约扣款。");
        } else {
            fundSource = "业主装修押金扣除";
            // 从装修押金扣除（以押金余额为限，不足部分物业垫付）
            BigDecimal deduct = e.reg.getDepositBalance().min(comp);
            if (deduct.compareTo(BigDecimal.ZERO) > 0) {
                e.reg.setDepositBalance(e.reg.getDepositBalance().subtract(deduct));
                regRepo.save(e.reg);
                DepositDeduction dd = billingService.saveDeduction(e.reg.getId(), null, null, "FINE", deduct,
                        "满溢投诉赔付扣款[" + e.complaint.getComplaintNo() + "]：业主超量未申报致垃圾点满溢，"
                                + "赔付用于邻居物业费减免", e.reg.getDepositBalance());
                c.setDepositDeductionId(dd.getId());
            }
            c.setDepositDeductedAmount(deduct);
            BigDecimal gap = comp.subtract(deduct);
            if (gap.compareTo(BigDecimal.ZERO) > 0) {
                Bill advance = new Bill();
                advance.setBillNo(NoGen.of("BILL"));
                advance.setBillType(BillType.PROPERTY_ADVANCE);
                advance.setPartyName("物业");
                advance.setRegistrationId(e.reg.getId());
                advance.setAppointmentId(e.appt == null ? null : e.appt.getId());
                advance.setPeriod(period);
                advance.setAmount(gap);
                advance.setStatus(BillStatus.PAID);
                advance.setPaidAmount(gap);
                advance.setPaidAt(LocalDateTime.now());
                advance.setDetail("业主押金不足以承担满溢赔付，物业垫付 " + gap
                        + " 元（投诉 " + e.complaint.getComplaintNo() + "），由业主补缴偿还");
                billRepo.save(advance);
                c.setPropertyAdvanceBillId(advance.getId());
            }
            // 通知业主补缴（按赔付全额挂账，补缴后押金回补）
            Bill topUp = new Bill();
            topUp.setBillNo(NoGen.of("BILL"));
            topUp.setBillType(BillType.OWNER);
            topUp.setPartyId(e.reg.getOwnerId());
            topUp.setPartyName(e.reg.getOwnerName());
            topUp.setRegistrationId(e.reg.getId());
            topUp.setAppointmentId(e.appt == null ? null : e.appt.getId());
            topUp.setPeriod(period);
            topUp.setAmount(comp);
            topUp.setDetail("满溢投诉[" + e.complaint.getComplaintNo() + "]业主超量未申报，需补缴装修押金 "
                    + comp + " 元（实际押金扣除 " + deduct + " 元"
                    + (gap.compareTo(BigDecimal.ZERO) > 0 ? "，物业垫付 " + gap + " 元" : "")
                    + "），补缴后押金回补");
            billRepo.save(topUp);
            c.setTopUpDue(comp);
            c.setTopUpBillId(topUp.getId());

            compBill.setDetail("资金来源：业主[" + e.reg.getOwnerName() + "]装修押金扣除 " + deduct + " 元"
                    + (gap.compareTo(BigDecimal.ZERO) > 0 ? " + 物业垫付 " + gap + " 元（业主补缴挂账单 "
                            + topUp.getBillNo() + "）" : "（业主补缴挂账单 " + topUp.getBillNo() + "）")
                    + "；入账去向：赔付给投诉人 " + e.complaint.getComplainantName() + "，用于减免物业费。");
            c.setReasonSummary("主要原因为业主超量未申报：施工队实际堆放超出申报"
                    + (e.overflowAtReview ? "且物业审核时垃圾点已超容量" : "（存在未申报的临时加袋）")
                    + "，赔付 " + comp + " 元从装修押金扣除，并通知业主补缴。");
        }
        billRepo.save(compBill);
        c.setCompensationBillId(compBill.getId());
        if (remark != null && !remark.isBlank()) {
            c.setReasonSummary(c.getReasonSummary() + " 物业备注：" + remark);
        }
        c.setStatus(CompensationStatus.DETERMINED);
        compensationRepo.save(c);

        // ---- 生成四方说明 ----
        generateNotices(c, e, fundSource, remark);

        // ---- 回写投诉：投诉人可看到处理结果 ----
        e.complaint.setCompensationId(c.getId());
        e.complaint.setStatus(ComplaintStatus.RESOLVED);
        e.complaint.setHandlerId(property.userId());
        e.complaint.setHandlerName(property.name());
        e.complaint.setResolution(c.getReasonSummary());
        e.complaint.setResolvedAt(LocalDateTime.now());
        complaintRepo.save(e.complaint);

        traceLogger.record(e.appt == null ? null : e.appt.getId(), "COMPENSATION", c.getId(), property,
                "COMPENSATION_DETERMINE", "投诉 " + e.complaint.getComplaintNo() + " 定责[" + liable
                        + "]，赔付 " + comp + " 元，扣款来源=" + fundSource + "，入账=物业费减免赔付单 "
                        + compBill.getBillNo());
        return c;
    }

    private void generateNotices(Compensation c, Evidence e, String fundSource, String remark) {
        String comp = money(c.getCompensationAmount());
        String base = "投诉单号 " + e.complaint.getComplaintNo() + "（垃圾点"
                + (c.getWastePointName() != null ? "[" + c.getWastePointName() + "]" : "")
                + "长期满溢、占用车位），处理时间 " + c.getDecidedAt() + "，经办人 " + c.getDecidedByName() + "。";

        // 邻居（投诉人）：只看处理结果与物业费减免，不暴露其他业主/公司资金细节
        String neighbor = base + "处理结果：经核查，主要原因为"
                + (c.getLiableParty() == LiabilityParty.COLLECTOR ? "清运公司清运延误" : "相关业主超量堆放")
                + "。物业已向您赔付 " + comp + " 元，用于减免相应金额的物业费，感谢您的监督。";
        saveNotice(c.getId(), "NEIGHBOR", "投诉处理结果通知（致邻居）", neighbor);

        // 清运公司
        String collector;
        if (c.getLiableParty() == LiabilityParty.COLLECTOR) {
            collector = base + "贵公司派单 " + c.getDispatchNo() + "（车牌 "
                    + (e.dispatch != null ? e.dispatch.getPlateNo() : "-") + "，司机 "
                    + (e.dispatch != null ? e.dispatch.getDriverName() : "-") + "）计划到场 "
                    + (e.dispatch != null ? e.dispatch.getPlannedArrival() : "-") + "，实际到场 "
                    + (e.dispatch != null ? e.dispatch.getActualArrival() : "-")
                    + "，构成清运延误并导致垃圾点满溢。本次赔付 " + comp
                    + " 元已计入贵公司履约扣款（在清运结算款中冲减），请加强排班与准时履约。";
        } else {
            collector = base + "经核查，本次满溢主要原因为业主超量未申报，贵公司派单 " + c.getDispatchNo()
                    + " 不承担主要赔付责任；请继续按预约准时清运。";
        }
        saveNotice(c.getId(), "COLLECTOR", "履约责任说明（致清运公司）", collector);

        // 业主
        String owner;
        if (c.getLiableParty() == LiabilityParty.OWNER) {
            owner = base + "经核查，您名下装修登记（" + c.getConstructionTeam() + " 施工）存在超量未申报：施工队实际堆放 "
                    + (e.report != null ? e.report.getBagCount() : 0) + " 袋（其中临时加袋 "
                    + (e.report != null ? e.report.getExtraBagCount() : 0) + " 袋）"
                    + (e.overflowAtReview ? "，物业审核时垃圾点已超容量，" : "，")
                    + "是本次满溢的主要原因。赔付 " + comp + " 元已从您的装修押金扣除（扣款流水 "
                    + (c.getDepositDeductionId() != null ? "#" + c.getDepositDeductionId() : "-") + "），"
                    + "请及时补缴装修押金 " + money(c.getTopUpDue()) + " 元（补缴账单 #" + c.getTopUpBillId()
                    + "，补缴后押金回补）。";
        } else {
            owner = base + "经核查，本次满溢主要原因为清运公司清运延误，您与施工队不承担本次赔付，装修押金不作扣除。";
        }
        saveNotice(c.getId(), "OWNER", "赔付责任说明（致业主）", owner);

        // 物业财务：扣款来源 + 入账去向
        String finance = base + "【扣款来源】" + fundSource + "，金额 " + comp + " 元。";
        if (c.getLiableParty() == LiabilityParty.COLLECTOR) {
            finance += "清运公司履约扣款单 #" + c.getCollectorDeductionBillId() + "（COLLECTOR 负数冲减结算款）。";
        } else {
            finance += "业主押金实际扣除 " + money(c.getDepositDeductedAmount()) + " 元（流水 #"
                    + c.getDepositDeductionId() + "）";
            if (c.getPropertyAdvanceBillId() != null) {
                finance += "，物业垫付账单 #" + c.getPropertyAdvanceBillId();
            }
            finance += "；业主补缴挂账单 #" + c.getTopUpBillId() + "（应缴 " + money(c.getTopUpDue())
                    + " 元，缴清后押金回补）。";
        }
        finance += "【入账去向】赔付资金账单 #" + c.getCompensationBillId()
                + "（COMPENSATION，已确认）定向用于投诉人 " + c.getComplainantName() + " 的物业费减免。";
        if (remark != null && !remark.isBlank()) {
            finance += " 物业备注：" + remark;
        }
        saveNotice(c.getId(), "FINANCE", "赔付扣款来源与入账说明（致物业财务）", finance);
    }

    private void saveNotice(Long compId, String audience, String title, String content) {
        CompensationNotice n = new CompensationNotice();
        n.setCompensationId(compId);
        n.setAudience(audience);
        n.setTitle(title);
        n.setContent(content);
        noticeRepo.save(n);
    }

    public List<CompensationNotice> noticesFor(Session session, Long complaintId) {
        Compensation c = compensationRepo.findByComplaintId(complaintId)
                .orElseThrow(() -> new BizException(404, "该投诉暂无赔付定责与说明"));
        String audience;
        switch (session.role()) {
            case FINANCE, ADMIN -> {
                return noticeRepo.findByCompensationIdOrderByIdAsc(c.getId());
            }
            case COLLECTOR -> audience = "COLLECTOR";
            case PROPERTY -> {
                return noticeRepo.findByCompensationIdOrderByIdAsc(c.getId());
            }
            case OWNER -> {
                // 投诉人（邻居业主）看 NEIGHBOR；涉事业主看 OWNER
                if (c.getComplainantId().equals(session.userId())) {
                    audience = "NEIGHBOR";
                } else if (c.getOwnerId() != null && c.getOwnerId().equals(session.userId())) {
                    audience = "OWNER";
                } else {
                    throw new BizException(403, "无权查看该赔付说明");
                }
            }
            default -> throw new BizException(403, "当前角色无权查看赔付说明");
        }
        return noticeRepo.findByCompensationIdAndAudience(c.getId(), audience);
    }

    public Compensation get(Long id) {
        return compensationRepo.findById(id).orElseThrow(() -> new BizException(404, "赔付责任分摊单不存在"));
    }

    public List<Compensation> list(Session session) {
        if (session.role() == Role.COLLECTOR && session.teamName() != null) {
            // 清运公司只见本公司承担履约扣款责任的单，不见业主责任单
            return compensationRepo.findByCompanyNameOrderByIdDesc(session.teamName()).stream()
                    .filter(c -> c.getLiableParty() == LiabilityParty.COLLECTOR).toList();
        }
        return compensationRepo.findAll();
    }

    /** 金额展示：去掉多余的尾随零（120.00 → 120） */
    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------
    // 证据串联
    // ------------------------------------------------------------------

    private Evidence loadEvidence(Long complaintId) {
        Complaint complaint = complaintRepo.findById(complaintId)
                .orElseThrow(() -> new BizException(404, "投诉不存在"));
        CollectionAppointment appt = complaint.getTargetAppointmentId() == null ? null
                : apptRepo.findById(complaint.getTargetAppointmentId()).orElse(null);
        if (appt == null && complaint.getTargetRegistrationId() != null) {
            appt = apptRepo.findByRegistrationIdOrderByIdDesc(complaint.getTargetRegistrationId())
                    .stream().findFirst().orElse(null);
        }
        DailyReport report = appt == null ? null : reportRepo.findById(appt.getReportId()).orElse(null);
        RenovationRegistration reg = complaint.getTargetRegistrationId() != null
                ? regRepo.findById(complaint.getTargetRegistrationId()).orElse(null)
                : appt != null ? regRepo.findById(appt.getRegistrationId()).orElse(null) : null;
        DispatchOrder dispatch = appt == null ? null
                : dispatchRepo.findByAppointmentId(appt.getId()).orElse(null);
        Long pointId = complaint.getWastePointId() != null ? complaint.getWastePointId()
                : (appt != null ? appt.getWastePointId() : null);
        WastePoint point = pointId == null ? null : wastePointRepo.findById(pointId).orElse(null);

        boolean late = dispatch != null && Boolean.TRUE.equals(dispatch.getLateFlag());
        boolean discharged = dispatch != null && dispatch.getStatus() == DispatchStatus.COMPLETED;
        int bagCount = report != null ? report.getBagCount() : 0;
        int extra = report != null ? report.getExtraBagCount() : 0;
        boolean ownerExtra = extra > 0;
        // 物业审核容量：审核时垃圾点是否已超有效容量
        boolean overflowAtReview = false;
        if (point != null && report != null) {
            int projected = point.getCurrentBags() + bagCount;
            overflowAtReview = projected > point.effectiveCapacity();
        }

        LiabilityParty suggested;
        String suggestion;
        if (overflowAtReview && ownerExtra) {
            suggested = LiabilityParty.OWNER;
            suggestion = "物业审核时垃圾点已超容量，且施工队实际堆放含 " + extra
                    + " 袋业主临时加袋（超量未申报），建议定责业主。";
        } else if (late) {
            suggested = LiabilityParty.COLLECTOR;
            suggestion = "清运公司派单" + (dispatch != null && dispatch.getActualArrival() != null ? "迟到" : "未到场")
                    + "，垃圾未能及时清运导致满溢，建议定责清运公司。";
        } else if (ownerExtra) {
            suggested = LiabilityParty.OWNER;
            suggestion = "存在业主临时加袋超量未申报，建议定责业主。";
        } else {
            suggested = null;
            suggestion = "现有证据无法自动区分主要责任，请物业人工判定。";
        }

        String timeline = buildTimeline(complaint, appt, report, reg, dispatch, point, late, discharged,
                ownerExtra, overflowAtReview, suggestion);
        return new Evidence(complaint, appt, report, reg, dispatch, point, late, discharged,
                ownerExtra, overflowAtReview, suggested, suggestion, timeline);
    }

    private String buildTimeline(Complaint complaint, CollectionAppointment appt, DailyReport report,
                                 RenovationRegistration reg, DispatchOrder dispatch, WastePoint point,
                                 boolean late, boolean discharged, boolean ownerExtra,
                                 boolean overflowAtReview, String suggestion) {
        StringBuilder sb = new StringBuilder("证据链时间线：");
        if (reg != null) {
            sb.append("①装修登记[").append(reg.getOwnerName()).append(" ").append(reg.getBuilding())
                    .append(reg.getRoomNumber()).append("，施工队 ").append(reg.getConstructionTeam()).append("]；");
        }
        if (report != null) {
            sb.append("②施工队实际上报堆放 ").append(report.getBagCount()).append(" 袋（申报 ")
                    .append(report.getBagCount() - report.getExtraBagCount()).append(" 袋、临时加袋 ")
                    .append(report.getExtraBagCount()).append(" 袋），堆放于[").append(report.getStackingLocation())
                    .append("]，上报时间 ").append(report.getCreatedAt()).append("；");
        }
        if (appt != null) {
            sb.append("③物业审核容量：审核人 ").append(appt.getReviewerName()).append("，预约 ")
                    .append(appt.getApptDate()).append(" ").append(appt.getTimeSlot());
            if (point != null) {
                sb.append("，垃圾点[").append(point.getName()).append("]容量 ").append(point.getCapacityBags())
                        .append(" 袋、有效容量 ").append(point.effectiveCapacity())
                        .append(overflowAtReview ? " 袋，审核时已超容量" : " 袋，审核时未超容量");
            }
            sb.append("；");
        }
        if (dispatch != null) {
            sb.append("④清运公司[").append(dispatch.getCompanyName()).append("]派单 ").append(dispatch.getDispatchNo())
                    .append("，计划到场 ").append(dispatch.getPlannedArrival()).append("，实际到场 ")
                    .append(dispatch.getActualArrival()).append(late ? "（迟到）" : "（准时）")
                    .append(discharged ? "，已完成清运" : "，尚未完成清运").append("；");
        }
        sb.append("⑤邻居投诉时间 ").append(complaint.getCreatedAt()).append("（投诉时清运")
                .append(discharged ? "已完成" : "未完成").append("，超量未申报=").append(ownerExtra)
                .append("）。结论建议：").append(suggestion);
        return sb.length() > 2000 ? sb.substring(0, 2000) : sb.toString();
    }

    /** 证据串联中间结构 */
    private record Evidence(Complaint complaint, CollectionAppointment appt, DailyReport report,
                            RenovationRegistration reg, DispatchOrder dispatch, WastePoint point,
                            boolean late, boolean discharged, boolean ownerExtra, boolean overflowAtReview,
                            LiabilityParty suggested, String suggestion, String timeline) {
    }
}
