package com.xiaoying.task.domain;

/** {@code tasks.status}（database.md §2.5，api.yaml TaskStatus）。SUCCEEDED / FAILED 为终态。 */
public enum TaskStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
