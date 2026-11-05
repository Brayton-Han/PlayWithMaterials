package com.xiaoying.simulation.web;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.xiaoying.common.error.FieldErrorDto;
import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.task.domain.TaskStage;

/**
 * {@code PUT /api/dev/simulation} 请求体（api.yaml SimulationConfig，整体替换）。
 * 契约要求 5 个字段都出现（failStage / failReason 可以为 null 但不能省略）且不允许额外字段，
 * 因此用 setter 记录字段是否出现、用 {@link JsonAnySetter} 收集未知字段，由 {@link #structuralErrors()} 报告。
 * setter 接收原始 JSON 值（{@code Object}）并严格检查类型：Jackson 默认会把 "1000" / 1.5 转成整数、把数字 1 转成枚举，
 * 这里一律作为字段错误返回，而不是静默转换。
 */
public final class SimulationConfigRequest {

    private static final String RANGE_MESSAGE = "需为 0 ~ " + SimulationConfig.MAX_STAGE_MS + " 之间的整数（毫秒）";
    private static final List<String> STAGE_NAMES = Arrays.stream(TaskStage.values()).map(Enum::name).toList();

    @NotNull(message = "请填写 assetCheckMs")
    @Min(value = 0, message = "assetCheckMs " + RANGE_MESSAGE)
    @Max(value = SimulationConfig.MAX_STAGE_MS, message = "assetCheckMs " + RANGE_MESSAGE)
    private Integer assetCheckMs;

    @NotNull(message = "请填写 generatingMs")
    @Min(value = 0, message = "generatingMs " + RANGE_MESSAGE)
    @Max(value = SimulationConfig.MAX_STAGE_MS, message = "generatingMs " + RANGE_MESSAGE)
    private Integer generatingMs;

    @NotNull(message = "请填写 resultOrganizingMs")
    @Min(value = 0, message = "resultOrganizingMs " + RANGE_MESSAGE)
    @Max(value = SimulationConfig.MAX_STAGE_MS, message = "resultOrganizingMs " + RANGE_MESSAGE)
    private Integer resultOrganizingMs;

    private TaskStage failStage;

    @Size(max = SimulationConfig.MAX_FAIL_REASON_LENGTH,
            message = "failReason 最长 " + SimulationConfig.MAX_FAIL_REASON_LENGTH + " 个字符")
    private String failReason;

    private boolean failStagePresent;
    private boolean failReasonPresent;
    private final List<FieldErrorDto> typeErrors = new ArrayList<>();
    private final Set<String> unknownFields = new LinkedHashSet<>();

    public void setAssetCheckMs(Object value) {
        this.assetCheckMs = duration("assetCheckMs", value);
    }

    public void setGeneratingMs(Object value) {
        this.generatingMs = duration("generatingMs", value);
    }

    public void setResultOrganizingMs(Object value) {
        this.resultOrganizingMs = duration("resultOrganizingMs", value);
    }

    public void setFailStage(Object value) {
        this.failStagePresent = true;
        if (value == null) {
            this.failStage = null;
        } else if (value instanceof String name && STAGE_NAMES.contains(name)) {
            this.failStage = TaskStage.valueOf(name);
        } else {
            typeErrors.add(FieldErrorDto.of("failStage", "INVALID_VALUE",
                    "failStage 只能是 " + String.join(" / ", STAGE_NAMES) + " 或 null"));
        }
    }

    public void setFailReason(Object value) {
        this.failReasonPresent = true;
        if (value == null || value instanceof String) {
            this.failReason = (String) value;
        } else {
            typeErrors.add(FieldErrorDto.of("failReason", "FORMAT", "failReason 需为字符串或 null"));
        }
    }

    /**
     * 只接受 JSON 整数。类型不对时记录错误并返回 0，避免 {@code @NotNull} 再报一次 REQUIRED。
     */
    private Integer duration(String field, Object value) {
        if (value == null || value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Long || value instanceof BigInteger) {
            typeErrors.add(FieldErrorDto.of(field, "OUT_OF_RANGE", field + " " + RANGE_MESSAGE));
        } else {
            typeErrors.add(FieldErrorDto.of(field, "FORMAT", field + " " + RANGE_MESSAGE));
        }
        return 0;
    }

    @JsonAnySetter
    void unknownField(String name, Object ignored) {
        unknownFields.add(name);
    }

    /** Bean Validation 覆盖不到的结构错误：类型不对、可空字段被省略、出现契约之外的字段。 */
    List<FieldErrorDto> structuralErrors() {
        List<FieldErrorDto> errors = new ArrayList<>(typeErrors);
        if (!failStagePresent) {
            errors.add(FieldErrorDto.of("failStage", "REQUIRED", "请填写 failStage（不失败时传 null）"));
        }
        if (!failReasonPresent) {
            errors.add(FieldErrorDto.of("failReason", "REQUIRED", "请填写 failReason（使用默认文案时传 null）"));
        }
        for (String name : unknownFields) {
            errors.add(FieldErrorDto.of(name, "INVALID_VALUE", "不支持的字段 " + name));
        }
        return errors;
    }

    /** 校验通过后调用。空白的 failReason 视为 null（使用按阶段的默认文案，与 application.yml 一致）。 */
    SimulationConfig toConfig() {
        String reason = (failReason == null || failReason.isBlank()) ? null : failReason;
        return new SimulationConfig(assetCheckMs, generatingMs, resultOrganizingMs, failStage, reason);
    }
}
