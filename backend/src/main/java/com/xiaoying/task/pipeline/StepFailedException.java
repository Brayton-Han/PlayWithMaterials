package com.xiaoying.task.pipeline;

import java.util.Objects;

import com.xiaoying.task.domain.FailureCode;

/**
 * 步骤的可预期失败（F-06 规则 3、7）：{@code failureCode} 机器可读，{@code reason} 面向用户（中文，OQ-11 临时默认），
 * 由 {@link TaskPipeline} 原样写入 {@code tasks.failure_code / failure_reason}。技术细节放在 cause 里，只写日志。
 */
public class StepFailedException extends RuntimeException {

    private final FailureCode code;
    private final String reason;

    public StepFailedException(FailureCode code, String reason) {
        this(code, reason, null);
    }

    public StepFailedException(FailureCode code, String reason, Throwable cause) {
        super(code + ": " + reason, cause);
        this.code = Objects.requireNonNull(code, "code");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        this.reason = reason;
    }

    public FailureCode code() {
        return code;
    }

    public String reason() {
        return reason;
    }
}
