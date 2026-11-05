package com.xiaoying.simulation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.xiaoying.task.domain.TaskStage;

/**
 * {@code app.simulation.*} 默认值（architecture §7），可被环境变量覆盖（如 {@code APP_SIMULATION_FAIL_STAGE}）。
 * 取值范围与 {@code PUT /api/dev/simulation} 相同（api.yaml SimulationConfig）：超出范围时启动失败，
 * 而不是带着非法默认值运行。运行时修改见 {@link SimulationSettings#update}（T-044）。
 */
@Validated
@ConfigurationProperties("app.simulation")
public record SimulationProperties(
        @Min(0) @Max(SimulationConfig.MAX_STAGE_MS) Long assetCheckMs,
        @Min(0) @Max(SimulationConfig.MAX_STAGE_MS) Long generatingMs,
        @Min(0) @Max(SimulationConfig.MAX_STAGE_MS) Long resultOrganizingMs,
        TaskStage failStage,
        @Size(max = SimulationConfig.MAX_FAIL_REASON_LENGTH) String failReason) {

    public SimulationProperties {
        if (assetCheckMs == null) {
            assetCheckMs = 1500L;
        }
        if (generatingMs == null) {
            generatingMs = 3000L;
        }
        if (resultOrganizingMs == null) {
            resultOrganizingMs = 1500L;
        }
        if (failReason != null && failReason.isBlank()) {
            failReason = null;
        }
    }

    SimulationConfig toConfig() {
        return new SimulationConfig(assetCheckMs, generatingMs, resultOrganizingMs, failStage, failReason);
    }
}
