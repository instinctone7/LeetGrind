ALTER TABLE study_groups ADD COLUMN visibility VARCHAR(7) NOT NULL DEFAULT 'PRIVATE',
    ADD KEY ix_group_discovery (visibility, closed_at, id),
    ADD CHECK (visibility IN ('PRIVATE', 'PUBLIC'));

CREATE TABLE challenges (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    creator_id BIGINT,
    title VARCHAR(80) NOT NULL,
    kind VARCHAR(12) NOT NULL,
    rule VARCHAR(16) NOT NULL,
    provider VARCHAR(16) NOT NULL,
    problem_slug VARCHAR(128),
    difficulty VARCHAR(8),
    target_count INT NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    cutoff_at DATETIME(6) NOT NULL,
    rule_version INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    finalized_at DATETIME(6),
    outcome VARCHAR(16),
    KEY ix_challenge_due (finalized_at, cutoff_at, id),
    KEY ix_group_challenges (group_id, id),
    FOREIGN KEY (group_id) REFERENCES study_groups (id),
    FOREIGN KEY (creator_id) REFERENCES accounts (id) ON DELETE SET NULL,
    CHECK (kind IN ('GROUP', 'DUEL')),
    CHECK (provider IN ('FIXTURE', 'LEETCODE')),
    CHECK (rule IN ('PROBLEM', 'COUNT', 'DIFFICULTY')),
    CHECK (target_count BETWEEN 1 AND 100 AND ends_at > starts_at AND cutoff_at >= ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE challenge_participants (
    challenge_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    enrolled_at DATETIME(6),
    final_score INT,
    winner BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (challenge_id, account_id),
    FOREIGN KEY (challenge_id) REFERENCES challenges (id),
    FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE challenge_evidence (
    challenge_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    solve_id BIGINT NOT NULL,
    PRIMARY KEY (challenge_id, account_id, solve_id),
    FOREIGN KEY (challenge_id, account_id) REFERENCES challenge_participants (challenge_id, account_id) ON DELETE CASCADE,
    FOREIGN KEY (solve_id) REFERENCES solves (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient_id BIGINT NOT NULL,
    kind VARCHAR(24) NOT NULL,
    source_key VARCHAR(128) NOT NULL,
    group_id BIGINT,
    challenge_id BIGINT,
    label VARCHAR(160),
    created_at DATETIME(6) NOT NULL,
    read_at DATETIME(6),
    UNIQUE KEY uk_notification_trigger (recipient_id, kind, source_key),
    KEY ix_notification_inbox (recipient_id, id),
    FOREIGN KEY (recipient_id) REFERENCES accounts (id) ON DELETE CASCADE,
    FOREIGN KEY (group_id) REFERENCES study_groups (id),
    FOREIGN KEY (challenge_id) REFERENCES challenges (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
