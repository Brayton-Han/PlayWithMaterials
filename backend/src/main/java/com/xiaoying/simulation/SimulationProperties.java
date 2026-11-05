package com.xiaoying.simulation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.xiaoying.task.domain.TaskStage;

/**
 * {@code app.simulation.*} 默认值（architecture §7），可被环境变量覆盖（如 {@code APP_SIMULATION_FAIL_STAGE}）。
 * 运行时修改（{@code PUT /api/dev/simulation}）与参数校验由 T-044 提供。
 */
@ConfigurationProperties("app.simulation")
public record SimulationProperties(Long assetCheckMs, Long generatingMs, Long resultOrganizingMs,
                                   TaskStage failStage, String failReason) {

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
