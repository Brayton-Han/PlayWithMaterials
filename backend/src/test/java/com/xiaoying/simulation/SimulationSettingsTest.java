package com.xiaoying.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import com.xiaoying.task.domain.TaskStage;

/** T-044：{@code app.simulation.*} 绑定与校验、运行时替换与快照。 */
class SimulationSettingsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(SimulationSettings.class);

    @Test
    void defaultsWhenNothingIsConfigured() {
        runner.run(ctx -> assertThat(ctx.getBean(SimulationSettings.class).current())
                .isEqualTo(new SimulationConfig(1500, 3000, 1500, null, null)));
    }

    @Test
    void environmentStyleOverridesAreBound() {
        runner.withPropertyValues("app.simulation.asset-check-ms=0", "app.simulation.generating-ms=60000",
                        "app.simulation.result-organizing-ms=7", "app.simulation.fail-stage=RESULT_ORGANIZING",
                        "app.simulation.fail-reason=坏了")
                .run(ctx -> assertThat(ctx.getBean(SimulationSettings.class).current())
                        .isEqualTo(new SimulationConfig(0, 60000, 7, TaskStage.RESULT_ORGANIZING, "坏了")));
    }

    @Test
    void blankFailReasonFallsBackToNull() {
        runner.withPropertyValues("app.simulation.fail-reason=  ")
                .run(ctx -> assertThat(ctx.getBean(SimulationSettings.class).current().failReason()).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "app.simulation.asset-check-ms=-1",
            "app.simulation.generating-ms=60001",
            "app.simulation.result-organizing-ms=-100",
            "app.simulation.fail-stage=PAINTING"})
    void invalidDefaultsFailStartup(String property) {
        runner.withPropertyValues(property).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void failReasonLongerThan200FailsStartup() {
        runner.withPropertyValues("app.simulation.fail-reason=" + "x".repeat(201))
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void updateReplacesTheCurrentConfigAndTheSnapshot() {
        SimulationSettings settings = new SimulationSettings(new SimulationProperties(null, null, null, null, null));
        String before = settings.snapshotJson();

        SimulationConfig next = new SimulationConfig(1, 2, 3, TaskStage.GENERATING, "原因");
        settings.update(next);

        assertThat(settings.current()).isEqualTo(next);
        assertThat(SimulationSettings.parseSnapshot(settings.snapshotJson())).isEqualTo(next);
        assertThat(SimulationSettings.parseSnapshot(before))
                .isEqualTo(new SimulationConfig(1500, 3000, 1500, null, null));
    }

    @Test
    void concurrentReadersAlwaysSeeAWholeConfig() throws Exception {
        SimulationSettings settings = new SimulationSettings(new SimulationProperties(0L, 0L, 0L, null, null));
        SimulationConfig a = new SimulationConfig(0, 0, 0, null, null);
        SimulationConfig b = new SimulationConfig(9, 9, 9, TaskStage.ASSET_CHECK, "b");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> readers = new ArrayList<>();
            for (int r = 0; r < 3; r++) {
                readers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        SimulationConfig seen = SimulationSettings.parseSnapshot(settings.snapshotJson());
                        if (!seen.equals(a) && !seen.equals(b)) {
                            return false;
                        }
                    }
                    return true;
                }));
            }
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 500; i++) {
                    settings.update(i % 2 == 0 ? b : a);
                }
                return null;
            });
            start.countDown();
            for (Future<Boolean> reader : readers) {
                assertThat(reader.get(30, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
