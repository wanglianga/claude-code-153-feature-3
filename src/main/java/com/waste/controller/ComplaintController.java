package com.waste.controller;

import com.waste.auth.AuthService;
import com.waste.auth.Session;
import com.waste.common.ApiResponse;
import com.waste.entity.Compensation;
import com.waste.entity.CompensationNotice;
import com.waste.entity.Complaint;
import com.waste.enums.ComplaintStatus;
import com.waste.enums.ComplaintType;
import com.waste.enums.LiabilityParty;
import com.waste.enums.Role;
import com.waste.service.CompensationService;
import com.waste.service.ComplaintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Tag(name = "投诉", description = "邻居/业主投诉与物业处理；费用争议自动冻结账单；满溢投诉串联证据定责赔付与四方说明")
@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService complaintService;
    private final CompensationService compensationService;

    public record CreateRequest(
            @NotNull ComplaintType type,
            Long targetAppointmentId,
            Long targetRegistrationId,
            Long billId,
            Long wastePointId,
            BigDecimal feeWaiverAmount,
            @NotBlank String description) {
    }

    public record HandleRequest(@NotNull ComplaintStatus action, @NotBlank String resolution) {
    }

    /** 定责请求：责任方/金额可空（空则采用证据建议责任方与默认赔付金额） */
    public record AdjudicateRequest(LiabilityParty liableParty, BigDecimal amount, String remark) {
    }

    @Operation(summary = "发起投诉（费用争议需传 billId；满溢/占用可传 wastePointId 与 feeWaiverAmount 减免诉求）")
    @PostMapping
    public ApiResponse<Complaint> create(@RequestBody @Valid CreateRequest req) {
        Session user = AuthService.current();
        return ApiResponse.ok(complaintService.create(user, req.type(), req.targetAppointmentId(),
                req.targetRegistrationId(), req.billId(), req.wastePointId(), req.feeWaiverAmount(),
                req.description()));
    }

    @Operation(summary = "物业处理投诉")
    @PutMapping("/{id}/handle")
    public ApiResponse<Complaint> handle(@PathVariable Long id, @RequestBody @Valid HandleRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(complaintService.handle(property, id, req.action(), req.resolution()));
    }

    @Operation(summary = "投诉列表（物业/财务看全部，其他人看自己发起的）")
    @GetMapping
    public ApiResponse<List<Complaint>> list(@RequestParam(required = false) ComplaintStatus status) {
        return ApiResponse.ok(complaintService.list(AuthService.current(), status));
    }

    @Operation(summary = "满溢投诉定责调查：串联预约记录/清运迟到/物业容量审核/施工队堆放/投诉时间，给出建议责任方")
    @GetMapping("/{id}/investigation")
    public ApiResponse<Map<String, Object>> investigation(@PathVariable Long id) {
        AuthService.require(Role.PROPERTY, Role.FINANCE);
        return ApiResponse.ok(compensationService.investigate(id));
    }

    @Operation(summary = "物业定责并执行赔付分摊（COLLECTOR 清运履约扣款 / OWNER 押金扣除并通知补缴），生成四方说明")
    @PostMapping("/{id}/compensation")
    public ApiResponse<Compensation> adjudicate(@PathVariable Long id,
                                                @RequestBody(required = false) AdjudicateRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        AdjudicateRequest body = req != null ? req : new AdjudicateRequest(null, null, null);
        return ApiResponse.ok(compensationService.adjudicate(property, id, body.liableParty(),
                body.amount(), body.remark()));
    }

    @Operation(summary = "查看赔付说明（投诉人看处理结果，业主/清运公司看各自责任，财务看扣款来源与入账去向）")
    @GetMapping("/{id}/notices")
    public ApiResponse<List<CompensationNotice>> notices(@PathVariable Long id) {
        return ApiResponse.ok(compensationService.noticesFor(AuthService.current(), id));
    }

    @Operation(summary = "赔付责任分摊单列表（财务/物业看全部，清运公司看本公司）")
    @GetMapping("/compensations")
    public ApiResponse<List<Compensation>> compensations() {
        return ApiResponse.ok(compensationService.list(AuthService.current()));
    }

    @Operation(summary = "赔付责任分摊单详情（含扣款来源、入账去向、四方说明）")
    @GetMapping("/compensations/{cid}")
    public ApiResponse<Compensation> compensation(@PathVariable Long cid) {
        AuthService.require(Role.FINANCE, Role.PROPERTY);
        return ApiResponse.ok(compensationService.get(cid));
    }
}
