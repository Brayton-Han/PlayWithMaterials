package com.xiaoying.task.pipeline;

import java.util.List;

import com.xiaoying.catalog.domain.Template;
import com.xiaoying.simulation.SimulationConfig;
import com.xiaoying.task.domain.Task;
import com.xiaoying.task.domain.TaskAsset;
import com.xiaoying.task.domain.TaskStage;

/** 步骤单测共用的构造方法。 */
final class StepFixtures {

    static final long TASK_ID = 42L;
    static final long USER_ID = 7L;
    static final String TEMPLATE_ID = "demo-postcard";

    /** 不等待、不失败。 */
    static final SimulationConfig NO_SIMULATION = new SimulationConfig(0, 0, 0, null, null);

    private StepFixtures() {
    }

    static SimulationConfig failAt(TaskStage stage, String reason) {
        return new SimulationConfig(0, 0, 0, stage, reason);
    }

    static TaskContext context(SimulationConfig sim, List<TaskAsset> assets) {
        Task task = new Task(USER_ID, TEMPLATE_ID, "{}");
        task.setId(TASK_ID);
        return new TaskContext(task, assets, sim);
    }

    static Template template(int requiredImageCount, List<String> acceptedMimeTypes, long maxFileSizeBytes,
                             String sampleResultPath) {
        Template t = new Template();
        t.setId(TEMPLATE_ID);
        t.setRequiredImageCount(requiredImageCount);
        t.setAcceptedMimeTypes(acceptedMimeTypes);
        t.setMaxFileSizeBytes(maxFileSizeBytes);
        t.setSampleResultPath(sampleResultPath);
        return t;
    }
}
