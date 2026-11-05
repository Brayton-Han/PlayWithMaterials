package com.xiaoying.task.pipeline;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 任务执行线程池（architecture §5.2 规则 5、6）：有界、固定线程数，队列满时 {@code AbortPolicy}
 * （{@code execute} 抛 {@link org.springframework.core.task.TaskRejectedException}）[OQ-12]；
 * 线程名前缀 {@code task-exec-} 便于看日志。停机时不再接收新任务，等待已提交的任务最多 30 s，
 * 之后仍未结束的任务停在原状态 [OQ-13]。
 */
@Configuration
@EnableConfigurationProperties(TaskExecutorProperties.class)
public class TaskExecutorConfig {

    public static final String EXECUTOR_BEAN = "taskPipelineExecutor";
    public static final String THREAD_NAME_PREFIX = "task-exec-";
    static final int AWAIT_TERMINATION_SECONDS = 30;

    @Bean(EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor taskPipelineExecutor(TaskExecutorProperties properties) {
        return newExecutor(properties);
    }

    /** 也供单测直接构造（不启动 Spring 容器）；调用方负责 {@code initialize()}（Spring Bean 由容器初始化）。 */
    static ThreadPoolTaskExecutor newExecutor(TaskExecutorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.poolSize());
        executor.setMaxPoolSize(properties.poolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        return executor;
    }
}
