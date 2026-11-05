-- V1：初始表结构（docs/database.md §2）。已发布的迁移不再修改，变更只追加新迁移。
-- 约定：utf8mb4 / utf8mb4_0900_ai_ci；时间 DATETIME(3) 存 UTC；枚举用 VARCHAR + CHECK。
-- task_step_logs 属于 P1（V3），此处不建。

-- ---------- users ----------
CREATE TABLE users (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username      VARCHAR(20)     NOT NULL,
    email         VARCHAR(254)    NOT NULL,
    -- 未删除时为 LOWER(email)，删除后为 NULL：实现"邮箱只在未删除账户中唯一（不区分大小写）"
    active_email  VARCHAR(254)    GENERATED ALWAYS AS (IF(deleted_at IS NULL, LOWER(email), NULL)) STORED,
    password_hash CHAR(60)        NOT NULL,
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at    DATETIME(3)     NULL,
    PRIMARY KEY (id),
    -- 含已删除账户，_ci 排序规则使大小写不同的用户名冲突（OQ-29、OQ-31）
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_active_email (active_email),
    CONSTRAINT chk_users_username CHECK (REGEXP_LIKE(username, '^[A-Za-z0-9_]{3,20}$', 'c'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------- sessions ----------
CREATE TABLE sessions (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    token_hash CHAR(64)        NOT NULL COMMENT 'SHA-256 hex of the cookie token; plaintext never stored',
    user_id    BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sessions_token_hash (token_hash),
    KEY idx_sessions_user_id (user_id),
    CONSTRAINT fk_sessions_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------- categories ----------
CREATE TABLE categories (
    id         VARCHAR(32) NOT NULL,
    name       VARCHAR(50) NOT NULL,
    sort_order INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_categories_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------- templates ----------
CREATE TABLE templates (
    id                   VARCHAR(64)      NOT NULL,
    category_id          VARCHAR(32)      NOT NULL,
    title                VARCHAR(100)     NOT NULL,
    description          VARCHAR(500)     NOT NULL DEFAULT '',
    cover_url            VARCHAR(255)     NOT NULL,
    sample_result_path   VARCHAR(255)     NOT NULL COMMENT 'classpath-relative preset result image; never exposed via API',
    required_image_count TINYINT UNSIGNED NOT NULL,
    accepted_mime_types  JSON             NOT NULL,
    max_file_size_bytes  INT UNSIGNED     NOT NULL,
    sort_order           INT              NOT NULL DEFAULT 0,
    created_at           DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_templates_category_sort (category_id, sort_order),
    CONSTRAINT fk_templates_categories FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT chk_templates_image_count CHECK (required_image_count BETWEEN 1 AND 9),
    CONSTRAINT chk_templates_max_size CHECK (max_file_size_bytes > 0),
    CONSTRAINT chk_templates_mime_types CHECK (JSON_TYPE(accepted_mime_types) = 'ARRAY' AND JSON_LENGTH(accepted_mime_types) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------- tasks ----------
CREATE TABLE tasks (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id        BIGINT UNSIGNED NOT NULL,
    template_id    VARCHAR(64)     NOT NULL,
    status         VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    stage          VARCHAR(24)     NULL,
    failed_stage   VARCHAR(24)     NULL,
    failure_code   VARCHAR(32)     NULL,
    failure_reason VARCHAR(255)    NULL,
    result_url     VARCHAR(255)    NULL,
    sim_config     JSON            NOT NULL COMMENT 'simulation snapshot at creation; never exposed via API',
    created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at     DATETIME(3)     NULL,
    finished_at    DATETIME(3)     NULL,
    updated_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_tasks_user_created (user_id, created_at, id),
    KEY idx_tasks_user_template_created (user_id, template_id, created_at, id),
    KEY idx_tasks_template_id (template_id),
    KEY idx_tasks_status (status),
    CONSTRAINT fk_tasks_users FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tasks_templates FOREIGN KEY (template_id) REFERENCES templates (id) ON DELETE RESTRICT,
    CONSTRAINT chk_tasks_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_tasks_stage CHECK (stage IS NULL OR stage IN ('ASSET_CHECK', 'GENERATING', 'RESULT_ORGANIZING')),
    CONSTRAINT chk_tasks_failed_stage CHECK (failed_stage IS NULL OR failed_stage IN ('ASSET_CHECK', 'GENERATING', 'RESULT_ORGANIZING')),
    CONSTRAINT chk_tasks_succeeded CHECK (status <> 'SUCCEEDED' OR result_url IS NOT NULL),
    CONSTRAINT chk_tasks_failed CHECK (status <> 'FAILED' OR (failure_code IS NOT NULL AND failure_reason IS NOT NULL)),
    CONSTRAINT chk_tasks_terminal_time CHECK (status NOT IN ('SUCCEEDED', 'FAILED') OR finished_at IS NOT NULL)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------- task_assets ----------
CREATE TABLE task_assets (
    id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    task_id       BIGINT UNSIGNED  NOT NULL,
    slot_index    TINYINT UNSIGNED NOT NULL,
    storage_key   VARCHAR(255)     NOT NULL COMMENT 'path relative to app.storage.root, e.g. uploads/2026/11/{uuid}.png',
    original_name VARCHAR(255)     NOT NULL,
    mime_type     VARCHAR(32)      NOT NULL COMMENT 'detected by magic bytes, not client Content-Type',
    size_bytes    INT UNSIGNED     NOT NULL,
    created_at    DATETIME(3)      NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_assets_task_slot (task_id, slot_index),
    UNIQUE KEY uk_task_assets_storage_key (storage_key),
    -- 仅用于提交失败时的补偿删除；正常业务不删除任务
    CONSTRAINT fk_task_assets_tasks FOREIGN KEY (task_id) REFERENCES tasks (id) ON DELETE CASCADE,
    CONSTRAINT chk_task_assets_size CHECK (size_bytes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
