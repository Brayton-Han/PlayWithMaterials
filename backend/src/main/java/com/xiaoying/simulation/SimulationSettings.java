package com.xiaoying.simulation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 当前生效的全局模拟配置（architecture §5.3，内存 {@link AtomicReference}，线程安全）。
 * 启动时取 {@link SimulationProperties}；dev / demo profile 下可由 {@code PUT /api/dev/simulation} 运行时替换（T-044）。
 */
@Component
@EnableConfigurationProperties(SimulationProperties.class)
public class SimulationSettings {

    private static final Logger log = LoggerFactory.getLogger(SimulationSettings.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AtomicReference<SimulationConfig> current;

    public SimulationSettings(SimulationProperties properties) {
        this.current = new AtomicReference<>(properties.toConfig());
    }

    public SimulationConfig current() {
        return current.get();
    }

    /**
     * 整体替换当前配置（{@code PUT /api/dev/simulation}）。只影响之后创建的任务 [OQ-10]；不持久化，
     * 进程重启后回到 {@link SimulationProperties}。调用方负责校验取值范围。
     */
    public SimulationConfig update(SimulationConfig config) {
        current.set(Objects.requireNonNull(config, "config"));
        log.info("Simulation config updated: {}", config);
        return config;
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
