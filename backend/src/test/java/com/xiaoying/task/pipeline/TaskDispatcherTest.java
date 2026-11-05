package com.xiaoying.task.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 有界线程池 + 派发（architecture §5.2 规则 5，OQ-12）：派发立即返回、在 {@code task-exec-} 线程执行；
 * 线程与队列都满时拒绝（{@link TaskRejectedException}，提交接口据此返回 503），被拒绝的任务不会执行。
 */
class TaskDispatcherTest {

    TaskPipeline pipeline = mock(TaskPipeline.class);
    ThreadPoolTaskExecutor executor;

    @AfterEach
    void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    void defaultsMatchOq12AndConfigUsesThem() {
        TaskExecutorProperties defaults = new TaskExecutorProperties(null, null);
        assertThat(defaults.poolSize()).isEqualTo(2);
        assertThat(defaults.queueCapacity()).isEqualTo(100);

        executor = TaskExecutorConfig.newExecutor(defaults);
        executor.initialize();
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
        assertThat(executor.getQueueCapacity()).isEqualTo(100);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("task-exec-");
    }

    @Test
    void dispatchReturnsImmediatelyAndRunsPipelineOnTaskExecThread() throws Exception {
        executor = start(1, 10);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        ConcurrentHashMap<Long, String> threads = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            threads.put(inv.getArgument(0), Thread.currentThread().getName());
            release.await(5, TimeUnit.SECONDS);
            done.countDown();
            return null;
        }).when(pipeline).run(anyLong());

        long start = System.nanoTime();
        new TaskDispatcher(executor, pipeline).dispatch(7L);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1000);
        assertThat(done.getCount()).isEqualTo(1);   // 派发时没有等待执行

        release.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threads.get(7L)).startsWith("task-exec-");
    }

    @Test
    void rejectsWhenThreadsAndQueueAreFullAndRejectedTaskNeverRuns() throws Exception {
        executor = start(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        doAnswer(inv -> {
            running.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(pipeline).run(anyLong());
        TaskDispatcher dispatcher = new TaskDispatcher(executor, pipeline);

        dispatcher.dispatch(1L);                 // 占用唯一的线程
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        dispatcher.dispatch(2L);                 // 进入队列
        assertThatThrownBy(() -> dispatcher.dispatch(3L)).isInstanceOf(TaskRejectedException.class);

        release.countDown();
        executor.shutdown();                     // 等待已受理的任务执行完
        executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS);
        verify(pipeline).run(1L);
        verify(pipeline).run(2L);
        verify(pipeline, never()).run(3L);
    }

    private static ThreadPoolTaskExecutor start(int poolSize, int queueCapacity) {
        ThreadPoolTaskExecutor e = TaskExecutorConfig.newExecutor(new TaskExecutorProperties(poolSize, queueCapacity));
        e.initialize();
        return e;
    }
}
