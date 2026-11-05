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

    /**
     * 读取任务创建时的快照（{@link #snapshotJson()} 的逆操作），供执行器使用 [OQ-10]：执行器只看快照，不看当前全局配置。
     *
     * @throws IllegalArgumentException 快照为空或不是合法的配置 JSON
     */
    public static SimulationConfig parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("simulation snapshot is empty");
        }
        try {
            return JSON.readValue(json, SimulationConfig.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid simulation snapshot: " + e.getMessage(), e);
        }
    }
}
