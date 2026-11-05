package com.xiaoying.simulation;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 当前生效的全局模拟配置（architecture §5.3，内存 {@link AtomicReference}，线程安全）。
 * 启动时取 {@link SimulationProperties}；运行时修改接口由 T-044 增加。
 */
@Component
@EnableConfigurationProperties(SimulationProperties.class)
public class SimulationSettings {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AtomicReference<SimulationConfig> current;

    public SimulationSettings(SimulationProperties properties) {
        this.current = new AtomicReference<>(properties.toConfig());
    }

    public SimulationConfig current() {
        return current.get();
    }

    /**
     * 创建任务时的快照（写入 {@code tasks.sim_config}）：
     * {@code {"assetCheckMs":1500,"generatingMs":3000,"resultOrganizingMs":1500,"failStage":null,"failReason":null}}
     * （database.md §2.5）。null 字段也输出，便于执行器按固定结构读取。
     */
    public String snapshotJson() {
        return JSON.writeValueAsString(current.get());
    }
}
