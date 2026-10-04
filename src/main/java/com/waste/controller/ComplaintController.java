package com.waste.controller;

import com.waste.auth.AuthService;
import com.waste.auth.Session;
import com.waste.common.ApiResponse;
import com.waste.entity.CompensationCase;
import com.waste.entity.Complaint;
import com.waste.enums.ComplaintStatus;
import com.waste.enums.ComplaintType;
import com.waste.enums.LiableParty;
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

@Tag(name = "投诉", description = "邻居/业主投诉与物业处理；费用争议自动冻结账单；满溢占车位投诉定责赔付与责任分摊")
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
            BigDecimal claimAmount,
            @NotBlank String description) {
    }

    public record HandleRequest(@NotNull ComplaintStatus action, @NotBlank String resolution) {
    }

    public record AssessRequest(
            LiableParty liableParty,        // 可空：空=系统按证据链自动定责，传值=人工指定
            BigDecimal compensationAmount,  // 可空：空=按投诉人主张金额核定
            String note) {
    }

    @Operation(summary = "发起投诉（费用争议需传 billId；满溢占车位需传 wastePointId + claimAmount）")
    @PostMapping
    public ApiResponse<Complaint> create(@RequestBody @Valid CreateRequest req) {
        Session user = AuthService.current();
        return ApiResponse.ok(complaintService.create(user, req.type(), req.targetAppointmentId(),
                req.targetRegistrationId(), req.billId(), req.wastePointId(), req.claimAmount(),
                req.description()));
    }

    @Operation(summary = "物业处理投诉")
    @PutMapping("/{id}/handle")
    public ApiResponse<Complaint> handle(@PathVariable Long id, @RequestBody @Valid HandleRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        return ApiResponse.ok(complaintService.handle(property, id, req.action(), req.resolution()));
    }

    @Operation(summary = "满溢占车位投诉定责赔付：串联预约/清运迟到/审核容量/实际堆放/投诉时间，"
            + "自动定责并执行赔付（清运公司履约扣款 / 业主押金扣除并通知补缴 / 物业承担），生成四方说明")
    @PostMapping("/{id}/assess")
    public ApiResponse<CompensationCase> assess(@PathVariable Long id, @RequestBody AssessRequest req) {
        Session property = AuthService.require(Role.PROPERTY);
        LiableParty party = req != null ? req.liableParty() : null;
        BigDecimal amount = req != null ? req.compensationAmount() : null;
        String note = req != null ? req.note() : null;
        return ApiResponse.ok(compensationService.assess(property, id, party, amount, note));
    }

    @Operation(summary = "查看赔付责任分摊单（投诉人看处理结果，财务看扣款来源与入账去向，责任方看各自说明）")
    @GetMapping("/{id}/compensation")
    public ApiResponse<Map<String, Object>> compensation(@PathVariable Long id) {
        return ApiResponse.ok(compensationService.view(AuthService.current(), id));
    }

    @Operation(summary = "投诉列表（物业/财务看全部，其他人看自己发起的）")
    @GetMapping
    public ApiResponse<List<Complaint>> list(@RequestParam(required = false) ComplaintStatus status) {
        return ApiResponse.ok(complaintService.list(AuthService.current(), status));
    }
}
