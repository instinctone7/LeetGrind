CREATE TABLE provider_identities (
    provider VARCHAR(16) NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    account_id BIGINT NOT NULL,
    PRIMARY KEY (provider, provider_account_id),
    UNIQUE KEY uk_provider_bound_account (provider, provider_account_id, account_id),
    FOREIGN KEY (account_id) REFERENCES accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE leetcode_links (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    handle VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    provider VARCHAR(16),
    provider_account_id VARCHAR(128),
    verified_at DATETIME(6),
    activated_at DATETIME(6),
    disconnected_at DATETIME(6),
    last_success_at DATETIME(6),
    checkpoint_at DATETIME(6),
    coverage_uncertain BOOLEAN NOT NULL DEFAULT TRUE,
    active_account_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN state <> 'DISCONNECTED' THEN account_id ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_link (active_account_id),
    KEY ix_link_history (account_id, id),
    FOREIGN KEY (account_id) REFERENCES accounts (id),
    FOREIGN KEY (provider, provider_account_id, account_id)
        REFERENCES provider_identities (provider, provider_account_id, account_id),
    CHECK (state IN ('PENDING', 'VERIFIED', 'DISCONNECTED')),
    CHECK (state <> 'VERIFIED' OR (
        provider IS NOT NULL AND provider_account_id IS NOT NULL
        AND verified_at IS NOT NULL AND activated_at IS NOT NULL
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE sync_jobs (
    link_id BIGINT NOT NULL,
    link_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    next_run_at DATETIME(6) NOT NULL,
    lease_token VARCHAR(36),
    lease_expires_at DATETIME(6),
    last_attempt_at DATETIME(6),
    manual_available_at DATETIME(6),
    failure_code VARCHAR(32),
    attempts INT NOT NULL DEFAULT 0,
    PRIMARY KEY (link_id, link_version),
    KEY ix_job_due (state, next_run_at),
    KEY ix_job_lease (state, lease_expires_at),
    FOREIGN KEY (link_id) REFERENCES leetcode_links (id),
    CHECK (state IN ('QUEUED', 'RUNNING', 'IDLE', 'RETRY', 'PAUSED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE problems (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider VARCHAR(16) NOT NULL,
    provider_problem_id VARCHAR(128) NOT NULL,
    slug VARCHAR(128) NOT NULL,
    title VARCHAR(160) NOT NULL,
    difficulty VARCHAR(8) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_problem (provider, provider_problem_id),
    CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE submissions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    link_id BIGINT NOT NULL,
    provider VARCHAR(16) NOT NULL,
    provider_account_id VARCHAR(128) NOT NULL,
    provider_submission_id VARCHAR(128) NOT NULL,
    problem_id BIGINT NOT NULL,
    outcome VARCHAR(8) NOT NULL,
    difficulty VARCHAR(8) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_provider_submission (provider, provider_account_id, provider_submission_id),
    FOREIGN KEY (link_id) REFERENCES leetcode_links (id),
    FOREIGN KEY (problem_id) REFERENCES problems (id),
    FOREIGN KEY (provider, provider_account_id, account_id)
        REFERENCES provider_identities (provider, provider_account_id, account_id),
    CHECK (outcome IN ('ACCEPTED', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE solves (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    submission_id BIGINT NOT NULL,
    difficulty VARCHAR(8) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_problem (account_id, problem_id),
    UNIQUE KEY uk_solve_submission (submission_id),
    KEY ix_solves_account_time (account_id, occurred_at),
    FOREIGN KEY (account_id) REFERENCES accounts (id),
    FOREIGN KEY (problem_id) REFERENCES problems (id),
    FOREIGN KEY (submission_id) REFERENCES submissions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE xp_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    solve_id BIGINT NOT NULL,
    provider VARCHAR(16) NOT NULL,
    reward_key VARCHAR(80) NOT NULL,
    reason VARCHAR(24) NOT NULL,
    amount INT NOT NULL,
    rule_version INT NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_xp_reward (account_id, reward_key),
    KEY ix_xp_account_time (account_id, effective_at),
    KEY ix_xp_account_history (account_id, id),
    FOREIGN KEY (account_id) REFERENCES accounts (id),
    FOREIGN KEY (solve_id) REFERENCES solves (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(32) NOT NULL,
    event_version INT NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload JSON NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    dispatched_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_aggregate (event_type, aggregate_id),
    KEY ix_outbox_dispatch (dispatched_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
