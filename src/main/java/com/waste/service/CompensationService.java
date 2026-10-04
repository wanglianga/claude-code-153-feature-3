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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投诉赔付与责任分摊：
 * 邻居投诉垃圾点长期满溢占用车位并要求减免物业费时，串联预约记录、清运公司迟到、
 * 物业审核容量、施工队实际堆放与投诉时间形成证据链，自动（或人工指定）定责：
 * - 清运延误 → 赔付计入清运公司履约扣款（COLLECTOR_DEDUCTION 扣款单，结算款抵扣）；
 * - 业主超量未申报 → 从装修押金扣除并生成押金补缴单通知业主补缴（押金不足部分物业垫付）；
 * - 其余 → 物业承担，计入物业运营成本。
 * 定责后生成给业主、邻居（投诉人）、清运公司、物业财务的四方说明：
 * 投诉人看到处理结果，财务看到扣款来源和入账去向。
 */
@Service
@RequiredArgsConstructor
public class CompensationService {

    private final ComplaintRepository complaintRepo;
    private final CompensationCaseRepository caseRepo;
    private final WastePointRepository wastePointRepo;
    private final CollectionAppointmentRepository apptRepo;
    private final DispatchOrderRepository dispatchRepo;
    private final DailyReportRepository reportRepo;
    private final WeighRecordRepository weighRepo;
    private final RenovationRegistrationRepository regRepo;
    private final BillRepository billRepo;
    private final BillingService billingService;
    private final WasteProperties props;
    private final TraceLogger traceLogger;

    /** 物业定责并执行赔付：串联证据链 → 定责 → 赔付入账 → 生成四方说明 → 投诉办结 */
    @Transactional
    public CompensationCase assess(Session property, Long complaintId, LiableParty overrideParty,
                                   BigDecimal compensationAmount, String note) {
        Complaint c = complaintRepo.findById(complaintId)
                .orElseThrow(() -> new BizException(404, "投诉不存在"));
        if (c.getType() != ComplaintType.OVERFLOW_PARKING) {
            throw new BizException("仅“垃圾点满溢占车位”类型的投诉可走赔付定责，当前类型 " + c.getType());
        }
        caseRepo.findByComplaintId(complaintId).ifPresent(k -> {
            throw new BizException("该投诉已生成赔付责任分摊单（" + k.getCaseNo() + "），不能重复定责");
        });
        if (c.getWastePointId() == null) {
            throw new BizException("投诉未关联垃圾点，无法定责");
        }
        WastePoint point = wastePointRepo.findById(c.getWastePointId())
                .orElseThrow(() -> new BizException(404, "垃圾点不存在"));
        BigDecimal amount = compensationAmount != null ? compensationAmount : c.getClaimAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("核定赔付金额必须大于 0");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);

        // ---- 串联证据链：投诉时间窗口内的预约、清运迟到、审核容量、实际堆放、称重折算 ----
        LocalDateTime windowEnd = c.getCreatedAt();
        LocalDateTime windowStart = windowEnd.minusDays(props.getCompensationWindowDays());
        Evidence ev = collectEvidence(point, windowStart, windowEnd);

        // ---- 定责：人工指定优先，否则按证据自动判定 ----
        LiableParty party;
        boolean auto = overrideParty == null;
        String reason;
        if (!auto) {
            party = overrideParty;
            reason = "物业人工定责[" + partyName(party) + "]"
                    + (note != null && !note.isBlank() ? "：" + note : "（未填写说明）");
        } else if (!ev.lateDispatches.isEmpty()) {
            party = LiableParty.COLLECTOR;
            DispatchOrder first = ev.lateDispatches.get(0).order;
            reason = "调查窗口（" + windowStart.toLocalDate() + " ~ " + windowEnd.toLocalDate()
                    + "）内清运迟到 " + ev.lateDispatches.size() + " 次（如派单 " + first.getDispatchNo()
                    + " 计划 " + first.getPlannedArrival() + " 实际 " + first.getActualArrival()
                    + "），垃圾点[" + point.getName() + "]满溢未及时清运，认定主因为清运延误";
        } else if (!ev.overDeclared.isEmpty()) {
            party = LiableParty.OWNER;
            OverDeclared od = ev.overDeclared.get(0);
            reason = "调查窗口内无清运迟到，但称重记录 " + od.weigh.getManifestNo() + " 显示实际清运 "
                    + od.weigh.getNetWeightKg() + "kg（折算约 " + od.actualBags + " 袋），超出申报 "
                    + od.declaredBags + " 袋，认定主因为业主超量堆放未申报";
        } else {
            party = LiableParty.PROPERTY;
            reason = "调查窗口内无清运迟到、无超量未申报，垃圾点[" + point.getName() + "]当前 "
                    + point.getCurrentBags() + " 袋 / 容量 " + point.getCapacityBags()
                    + " 袋，认定物业容量审核与日常管理不当";
        }

        // ---- 赔付责任分摊单 ----
        CompensationCase kase = new CompensationCase();
        kase.setCaseNo(NoGen.of("CMP"));
        kase.setComplaintId(c.getId());
        kase.setComplaintNo(c.getComplaintNo());
        kase.setWastePointId(point.getId());
        kase.setWastePointName(point.getName());
        kase.setComplainantId(c.getComplainantId());
        kase.setComplainantName(c.getComplainantName());
        kase.setClaimAmount(c.getClaimAmount());
        kase.setCompensationAmount(amount);
        kase.setLiableParty(party);
        kase.setAutoDetermined(auto);
        kase.setLiabilityReason(reason);
        kase.setWindowStart(windowStart);
        kase.setWindowEnd(windowEnd);
        kase.setHandlerId(property.userId());
        kase.setHandlerName(property.name());
        kase.setCreditTarget("投诉人[" + c.getComplainantName() + "]物业费减免 " + amount + " 元");

        // ---- 执行赔付：扣款来源 → 入账去向 ----
        String period = LocalDate.now().toString().substring(0, 7);
        switch (party) {
            case COLLECTOR -> settleByCollector(kase, ev, amount, period);
            case OWNER -> settleByOwner(kase, ev, c, amount, period);
            case PROPERTY -> settleByProperty(kase, amount, period);
        }
        kase.setEvidence(ev.toJson(reason));
        buildStatements(kase, point, ev, amount);
        caseRepo.save(kase);

        // ---- 投诉办结 ----
        c.setStatus(ComplaintStatus.RESOLVED);
        c.setHandlerId(property.userId());
        c.setHandlerName(property.name());
        c.setResolution("定责[" + partyName(party) + "] " + reason + "；赔付 " + amount
                + " 元（赔付单 " + kase.getCaseNo() + "），" + kase.getCreditTarget());
        c.setResolvedAt(LocalDateTime.now());
        complaintRepo.save(c);

        traceLogger.record(c.getTargetAppointmentId(), "COMPLAINT", c.getId(), property, "COMPLAINT_ASSESS",
                "满溢占车位投诉定责[" + partyName(party) + "]: " + reason + "；赔付 " + amount + " 元");
        traceLogger.record(c.getTargetAppointmentId(), "COMPENSATION", kase.getId(), property,
                "COMPENSATION_SETTLE", "赔付单 " + kase.getCaseNo() + "：扣款来源=" + kase.getDeductionSource()
                        + "；入账去向=" + kase.getCreditTarget());
        return kase;
    }

    /** 清运延误 → 清运公司履约扣款（从清运结算款中抵扣） */
    private void settleByCollector(CompensationCase kase, Evidence ev, BigDecimal amount, String period) {
        String company = ev.lateDispatches.isEmpty()
                ? ev.lateDispatchesCompanyFallback : ev.lateDispatches.get(0).order.getCompanyName();
        kase.setCollectorName(company);

        Bill deduction = new Bill();
        deduction.setBillNo(NoGen.of("BILL"));
        deduction.setBillType(BillType.COLLECTOR_DEDUCTION);
        deduction.setPartyName(company);
        deduction.setPeriod(period);
        deduction.setAmount(amount);
        deduction.setDetail("{\"caseNo\":\"" + kase.getCaseNo() + "\",\"complaintNo\":\"" + kase.getComplaintNo()
                + "\",\"reason\":\"清运延误导致垃圾点满溢占车位，履约扣款\",\"lateDispatches\":"
                + ev.lateDispatches.size() + "}");
        billRepo.save(deduction);
        kase.setDeductionBillId(deduction.getId());
        kase.setDeductionSource("清运公司[" + company + "]履约扣款（扣款单 " + deduction.getBillNo()
                + "，从清运结算款中抵扣）");
    }

    /** 业主超量未申报 → 从装修押金扣除并生成补缴单通知业主补缴；押金不足部分物业垫付 */
    private void settleByOwner(CompensationCase kase, Evidence ev, Complaint c,
                               BigDecimal amount, String period) {
        Long regId = !ev.overDeclared.isEmpty() ? ev.overDeclared.get(0).registrationId
                : c.getTargetRegistrationId();
        if (regId == null) {
            throw new BizException("无法定位责任业主的装修登记（投诉未关联登记且窗口内无超量称重记录）");
        }
        RenovationRegistration reg = regRepo.findById(regId)
                .orElseThrow(() -> new BizException(404, "责任业主的装修登记不存在"));
        kase.setRegistrationId(reg.getId());
        kase.setOwnerId(reg.getOwnerId());
        kase.setOwnerName(reg.getOwnerName());

        // 从装修押金扣除赔付
        BigDecimal deduct = reg.getDepositBalance().min(amount);
        StringBuilder source = new StringBuilder();
        if (deduct.compareTo(BigDecimal.ZERO) > 0) {
            reg.setDepositBalance(reg.getDepositBalance().subtract(deduct));
            regRepo.save(reg);
            DepositDeduction dd = billingService.saveDeduction(reg.getId(), null, null, "COMP", deduct,
                    "投诉赔付扣款（赔付单 " + kase.getCaseNo() + "，投诉 " + kase.getComplaintNo() + "）",
                    reg.getDepositBalance());
            kase.setDepositDeductionId(dd.getId());
            source.append("业主[").append(reg.getOwnerName()).append("]装修押金（登记 ").append(reg.getRegNo())
                    .append("，押金流水 #").append(dd.getId()).append("）");
        } else {
            source.append("业主[").append(reg.getOwnerName()).append("]装修押金（余额不足，未扣到款）");
        }

        // 通知业主补缴：生成押金补缴单，缴清后押金回补
        if (deduct.compareTo(BigDecimal.ZERO) > 0) {
            Bill replenish = new Bill();
            replenish.setBillNo(NoGen.of("BILL"));
            replenish.setBillType(BillType.OWNER_REPLENISH);
            replenish.setPartyId(reg.getOwnerId());
            replenish.setPartyName(reg.getOwnerName());
            replenish.setRegistrationId(reg.getId());
            replenish.setPeriod(period);
            replenish.setAmount(deduct);
            replenish.setDetail("{\"caseNo\":\"" + kase.getCaseNo() + "\",\"complaintNo\":\""
                    + kase.getComplaintNo() + "\",\"reason\":\"投诉赔付从装修押金扣除，通知业主补缴押金\"}");
            billRepo.save(replenish);
            kase.setDeductionBillId(replenish.getId());
            source.append("；已生成押金补缴单 ").append(replenish.getBillNo());
        }

        // 押金不足部分物业垫付
        BigDecimal gap = amount.subtract(deduct);
        if (gap.compareTo(BigDecimal.ZERO) > 0) {
            Bill advance = new Bill();
            advance.setBillNo(NoGen.of("BILL"));
            advance.setBillType(BillType.PROPERTY_ADVANCE);
            advance.setPartyName("物业");
            advance.setRegistrationId(reg.getId());
            advance.setPeriod(period);
            advance.setAmount(gap);
            advance.setDetail("业主押金不足，物业垫付投诉赔付（赔付单 " + kase.getCaseNo() + "）");
            billRepo.save(advance);
            source.append("；押金不足部分 ").append(gap).append(" 元由物业垫付（垫付单 ")
                    .append(advance.getBillNo()).append("）");
        }
        kase.setDeductionSource(source.toString());
    }

    /** 物业责任 → 计入物业运营成本 */
    private void settleByProperty(CompensationCase kase, BigDecimal amount, String period) {
        Bill advance = new Bill();
        advance.setBillNo(NoGen.of("BILL"));
        advance.setBillType(BillType.PROPERTY_ADVANCE);
        advance.setPartyName("物业");
        advance.setPeriod(period);
        advance.setAmount(amount);
        advance.setDetail("投诉赔付支出（赔付单 " + kase.getCaseNo() + "，投诉 " + kase.getComplaintNo() + "）");
        billRepo.save(advance);
        kase.setDeductionBillId(advance.getId());
        kase.setDeductionSource("物业运营成本（垫付单 " + advance.getBillNo() + "）");
    }

    /** 生成给业主、邻居（投诉人）、清运公司、物业财务的四方说明 */
    private void buildStatements(CompensationCase kase, WastePoint point, Evidence ev, BigDecimal amount) {
        String base = "垃圾点[" + point.getName() + "]满溢占车位投诉（" + kase.getComplaintNo() + "）";
        String partyDesc = switch (kase.getLiableParty()) {
            case COLLECTOR -> "清运公司清运延误";
            case OWNER -> "业主超量堆放未申报";
            case PROPERTY -> "物业容量审核与日常管理不当";
        };

        // 给邻居/投诉人：处理结果
        kase.setNeighborStatement("您反映的" + base + "已处理完毕。经串联预约记录、清运到场时间、物业容量审核、"
                + "施工队实际堆放与投诉时间核查，认定主要原因为" + partyDesc + "。核定赔付 " + amount
                + " 元，已记入您的物业费减免（" + kase.getCreditTarget() + "）。给您带来不便，敬请谅解。");

        // 给业主
        if (kase.getLiableParty() == LiableParty.OWNER) {
            kase.setOwnerStatement("经核查，您名下装修（登记 " + regNoOf(kase) + "）实际堆放超出申报"
                    + overDeclaredDesc(ev) + "，导致" + base + "成立。已从您的装修押金扣除赔付 " + amount
                    + " 元（押金流水 " + (kase.getDepositDeductionId() != null ? "#" + kase.getDepositDeductionId() : "无")
                    + "），请于账单日前补缴押金（补缴单 "
                    + (kase.getDeductionBillId() != null ? billNoOf(kase.getDeductionBillId()) : "无")
                    + "），缴清后押金余额自动回补。");
        } else {
            kase.setOwnerStatement("经核查，" + base + "的主要原因为" + partyDesc
                    + "，与业主装修申报堆放无关，您无需承担本次赔付费用。");
        }

        // 给清运公司
        if (kase.getLiableParty() == LiableParty.COLLECTOR) {
            kase.setCollectorStatement("因贵司清运延误（" + lateDesc(ev) + "）导致" + base
                    + "成立，产生履约扣款 " + amount + " 元（扣款单 " + billNoOf(kase.getDeductionBillId())
                    + "），将从清运结算款中抵扣。请加强排班与到场时效管理。");
        } else {
            kase.setCollectorStatement("经核查，" + base + "的主要原因为" + partyDesc
                    + "，与贵司清运履约无关，本次不产生履约扣款。");
        }

        // 给物业财务：扣款来源与入账去向
        kase.setFinanceStatement("赔付单 " + kase.getCaseNo() + "（投诉 " + kase.getComplaintNo()
                + "）：核定赔付 " + amount + " 元，责任方=" + partyName(kase.getLiableParty())
                + "。扣款来源：" + kase.getDeductionSource() + "；入账去向：" + kase.getCreditTarget()
                + "。关联单据：" + (kase.getDeductionBillId() != null ? "账单 #" + kase.getDeductionBillId() : "无")
                + (kase.getDepositDeductionId() != null ? "、押金流水 #" + kase.getDepositDeductionId() : "") + "。");
    }

    private String regNoOf(CompensationCase kase) {
        return kase.getRegistrationId() == null ? "-" : regRepo.findById(kase.getRegistrationId())
                .map(RenovationRegistration::getRegNo).orElse("-");
    }

    private String billNoOf(Long billId) {
        return billId == null ? "-" : billRepo.findById(billId).map(Bill::getBillNo).orElse("-");
    }

    private String lateDesc(Evidence ev) {
        if (ev.lateDispatches.isEmpty()) {
            return "清运迟到";
        }
        LateDispatch ld = ev.lateDispatches.get(0);
        return "派单 " + ld.order.getDispatchNo() + " 计划 " + ld.order.getPlannedArrival()
                + " 实际 " + ld.order.getActualArrival() + "，迟到 " + ld.lateMinutes + " 分钟";
    }

    private String overDeclaredDesc(Evidence ev) {
        if (ev.overDeclared.isEmpty()) {
            return "";
        }
        OverDeclared od = ev.overDeclared.get(0);
        return "（申报 " + od.declaredBags + " 袋，称重折算约 " + od.actualBags + " 袋，超量约 "
                + (od.actualBags - od.declaredBags) + " 袋未申报）";
    }

    // ==================== 证据链串联 ====================

    private record LateDispatch(DispatchOrder order, long lateMinutes) {
    }

    private record OverDeclared(WeighRecord weigh, int declaredBags, int actualBags, Long registrationId) {
    }

    private class Evidence {
        List<CollectionAppointment> appointments = new java.util.ArrayList<>();
        List<DailyReport> reports = new java.util.ArrayList<>();
        List<LateDispatch> lateDispatches = new java.util.ArrayList<>();
        List<OverDeclared> overDeclared = new java.util.ArrayList<>();
        List<WeighRecord> weighs = new java.util.ArrayList<>();
        String lateDispatchesCompanyFallback = "清运公司";

        String toJson(String conclusion) {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"conclusion\":\"").append(esc(conclusion)).append("\",");
            sb.append("\"appointments\":[");
            for (int i = 0; i < appointments.size(); i++) {
                CollectionAppointment a = appointments.get(i);
                sb.append(i > 0 ? "," : "").append("{\"id\":").append(a.getId())
                        .append(",\"apptNo\":\"").append(a.getApptNo())
                        .append("\",\"apptDate\":\"").append(a.getApptDate())
                        .append("\",\"timeSlot\":\"").append(a.getTimeSlot())
                        .append("\",\"status\":\"").append(a.getStatus())
                        .append("\",\"reviewer\":\"").append(esc(a.getReviewerName()))
                        .append("\",\"reviewedAt\":\"").append(a.getReviewedAt())
                        .append("\",\"capacityMessage\":\"").append(esc(a.getCapacityMessage())).append("\"}");
            }
            sb.append("],\"reports\":[");
            for (int i = 0; i < reports.size(); i++) {
                DailyReport r = reports.get(i);
                sb.append(i > 0 ? "," : "").append("{\"id\":").append(r.getId())
                        .append(",\"reportDate\":\"").append(r.getReportDate())
                        .append("\",\"bagCount\":").append(r.getBagCount())
                        .append(",\"extraBagCount\":").append(r.getExtraBagCount())
                        .append(",\"submitter\":\"").append(esc(r.getSubmitterName()))
                        .append("\",\"stackingLocation\":\"").append(esc(r.getStackingLocation())).append("\"}");
            }
            sb.append("],\"lateDispatches\":[");
            for (int i = 0; i < lateDispatches.size(); i++) {
                LateDispatch ld = lateDispatches.get(i);
                DispatchOrder d = ld.order;
                sb.append(i > 0 ? "," : "").append("{\"dispatchNo\":\"").append(d.getDispatchNo())
                        .append("\",\"company\":\"").append(esc(d.getCompanyName()))
                        .append("\",\"plateNo\":\"").append(d.getPlateNo())
                        .append("\",\"plannedArrival\":\"").append(d.getPlannedArrival())
                        .append("\",\"actualArrival\":\"").append(d.getActualArrival())
                        .append("\",\"lateMinutes\":").append(ld.lateMinutes).append("}");
            }
            sb.append("],\"weighRecords\":[");
            for (int i = 0; i < weighs.size(); i++) {
                WeighRecord w = weighs.get(i);
                sb.append(i > 0 ? "," : "").append("{\"manifestNo\":\"").append(w.getManifestNo())
                        .append("\",\"netWeightKg\":").append(w.getNetWeightKg())
                        .append(",\"estimatedWeightKg\":").append(w.getEstimatedWeightKg())
                        .append(",\"anomalyFlag\":").append(w.getAnomalyFlag()).append("}");
            }
            sb.append("],\"lateDispatchCount\":").append(lateDispatches.size());
            sb.append(",\"overDeclaredWeighCount\":").append(overDeclared.size());
            sb.append("}");
            return sb.toString();
        }
    }

    /** 串联：垃圾点 + 投诉时间窗口内的预约记录、清运迟到、物业审核容量、施工队实际堆放、称重折算超量 */
    private Evidence collectEvidence(WastePoint point, LocalDateTime windowStart, LocalDateTime windowEnd) {
        Evidence ev = new Evidence();
        LocalDate startDate = windowStart.toLocalDate();
        LocalDate endDate = windowEnd.toLocalDate();

        // 预约记录（含物业审核容量信息）：窗口内创建或预约日落在窗口内
        List<CollectionAppointment> appts = apptRepo.findAll().stream()
                .filter(a -> a.getWastePointId().equals(point.getId()))
                .filter(a -> !a.getCreatedAt().isBefore(windowStart)
                        || (!a.getApptDate().isBefore(startDate) && !a.getApptDate().isAfter(endDate)))
                .toList();
        ev.appointments = appts;
        List<Long> apptIds = appts.stream().map(CollectionAppointment::getId).toList();

        // 清运迟到：窗口内预约的派单
        for (Long apptId : apptIds) {
            dispatchRepo.findByAppointmentId(apptId).ifPresent(d -> {
                if (Boolean.TRUE.equals(d.getLateFlag()) && d.getActualArrival() != null) {
                    ev.lateDispatches.add(new LateDispatch(d,
                            Duration.between(d.getPlannedArrival(), d.getActualArrival()).toMinutes()));
                }
                if (d.getCompanyName() != null) {
                    ev.lateDispatchesCompanyFallback = d.getCompanyName();
                }
            });
        }

        // 施工队实际堆放：窗口内该垃圾点的每日上报
        ev.reports = reportRepo.findAll().stream()
                .filter(r -> r.getWastePointId().equals(point.getId()))
                .filter(r -> !r.getCreatedAt().isBefore(windowStart)
                        || (!r.getReportDate().isBefore(startDate) && !r.getReportDate().isAfter(endDate)))
                .toList();

        // 称重记录：实际重量折算袋数 vs 申报袋数 → 超量未申报
        Map<Long, DailyReport> reportById = new LinkedHashMap<>();
        for (CollectionAppointment a : appts) {
            reportRepo.findById(a.getReportId()).ifPresent(r -> reportById.put(a.getId(), r));
        }
        BigDecimal overFactor = BigDecimal.ONE.add(props.getWeighAnomalyThresholdPct()
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        for (WeighRecord w : weighRepo.findAll()) {
            if (!apptIds.contains(w.getAppointmentId())) {
                continue;
            }
            ev.weighs.add(w);
            DailyReport report = reportById.get(w.getAppointmentId());
            if (report == null) {
                continue;
            }
            BigDecimal est = props.getAvgBagWeightKg().multiply(BigDecimal.valueOf(report.getBagCount()));
            if (w.getNetWeightKg().compareTo(est.multiply(overFactor)) > 0) {
                int actualBags = w.getNetWeightKg().divide(props.getAvgBagWeightKg(), 0, RoundingMode.HALF_UP)
                        .intValue();
                CollectionAppointment appt = appts.stream()
                        .filter(a -> a.getId().equals(w.getAppointmentId())).findFirst().orElseThrow();
                ev.overDeclared.add(new OverDeclared(w, report.getBagCount(), actualBags,
                        appt.getRegistrationId()));
            }
        }
        return ev;
    }

    // ==================== 按角色查看 ====================

    /** 查看赔付责任分摊单：投诉人看处理结果，财务看扣款来源与入账去向，各责任方看各自说明 */
    public Map<String, Object> view(Session session, Long complaintId) {
        CompensationCase kase = caseRepo.findByComplaintId(complaintId)
                .orElseThrow(() -> new BizException(404, "该投诉尚未生成赔付责任分摊单"));
        Role role = session.role();
        boolean full = role == Role.PROPERTY || role == Role.FINANCE || role == Role.ADMIN;
        boolean isComplainant = kase.getComplainantId().equals(session.userId());
        boolean isLiableOwner = kase.getOwnerId() != null && kase.getOwnerId().equals(session.userId());
        boolean isLiableCollector = role == Role.COLLECTOR && session.teamName() != null
                && session.teamName().equals(kase.getCollectorName());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseNo", kase.getCaseNo());
        m.put("complaintNo", kase.getComplaintNo());
        m.put("wastePointName", kase.getWastePointName());
        m.put("status", kase.getStatus());
        m.put("compensationAmount", kase.getCompensationAmount());
        m.put("liableParty", kase.getLiableParty());
        m.put("createdAt", kase.getCreatedAt());

        if (full) {
            // 物业/财务/管理员：完整单据（含证据链与四方说明）
            m.put("complaintId", kase.getComplaintId());
            m.put("complainantName", kase.getComplainantName());
            m.put("claimAmount", kase.getClaimAmount());
            m.put("autoDetermined", kase.getAutoDetermined());
            m.put("liabilityReason", kase.getLiabilityReason());
            m.put("windowStart", kase.getWindowStart());
            m.put("windowEnd", kase.getWindowEnd());
            m.put("deductionSource", kase.getDeductionSource());
            m.put("creditTarget", kase.getCreditTarget());
            m.put("deductionBillId", kase.getDeductionBillId());
            m.put("depositDeductionId", kase.getDepositDeductionId());
            m.put("registrationId", kase.getRegistrationId());
            m.put("ownerName", kase.getOwnerName());
            m.put("collectorName", kase.getCollectorName());
            m.put("ownerStatement", kase.getOwnerStatement());
            m.put("neighborStatement", kase.getNeighborStatement());
            m.put("collectorStatement", kase.getCollectorStatement());
            m.put("financeStatement", kase.getFinanceStatement());
            m.put("evidence", kase.getEvidence());
            m.put("handlerName", kase.getHandlerName());
            return m;
        }
        if (isComplainant) {
            // 投诉人（邻居）：处理结果
            m.put("liabilityReason", kase.getLiabilityReason());
            m.put("creditTarget", kase.getCreditTarget());
            m.put("neighborStatement", kase.getNeighborStatement());
            m.put("handlerName", kase.getHandlerName());
            return m;
        }
        if (isLiableOwner) {
            // 责任业主：押金扣除与补缴通知
            m.put("deductionSource", kase.getDeductionSource());
            m.put("depositDeductionId", kase.getDepositDeductionId());
            m.put("replenishBillId", kase.getDeductionBillId());
            m.put("ownerStatement", kase.getOwnerStatement());
            return m;
        }
        if (isLiableCollector) {
            // 责任清运公司：履约扣款说明
            m.put("deductionSource", kase.getDeductionSource());
            m.put("deductionBillId", kase.getDeductionBillId());
            m.put("collectorStatement", kase.getCollectorStatement());
            return m;
        }
        throw new BizException(403, "您与该赔付单无关，无权查看");
    }

    public CompensationCase getByComplaint(Long complaintId) {
        return caseRepo.findByComplaintId(complaintId)
                .orElseThrow(() -> new BizException(404, "该投诉尚未生成赔付责任分摊单"));
    }

    private static String partyName(LiableParty party) {
        return switch (party) {
            case COLLECTOR -> "清运公司";
            case OWNER -> "业主";
            case PROPERTY -> "物业";
        };
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "'")
                .replace("\n", " ").replace("\r", " ");
    }
}
