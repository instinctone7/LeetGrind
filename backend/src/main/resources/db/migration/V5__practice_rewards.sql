ALTER TABLE xp_entries MODIFY solve_id BIGINT NULL;

CREATE TABLE reward_windows (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(16) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    cutoff_at DATETIME(6) NOT NULL,
    rule_version INT NOT NULL,
    reward_xp INT NOT NULL,
    target_count INT NOT NULL,
    topic VARCHAR(40),
    eligible_problem_slugs TEXT,
    problem_slug VARCHAR(128),
    problem_title VARCHAR(160),
    problem_difficulty VARCHAR(8),
    problem_topics VARCHAR(160),
    UNIQUE KEY uk_reward_window (provider, kind, starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE reward_evidence (
    window_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    solve_id BIGINT NOT NULL,
    PRIMARY KEY (window_id, account_id, solve_id),
    FOREIGN KEY (window_id) REFERENCES reward_windows (id),
    FOREIGN KEY (solve_id) REFERENCES solves (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE user_achievements (
    account_id BIGINT NOT NULL,
    provider VARCHAR(16) NOT NULL,
    code VARCHAR(40) NOT NULL,
    rule_version INT NOT NULL,
    unlocked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (account_id, provider, code),
    FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE level_thresholds (
    rule_version INT NOT NULL,
    level_number INT NOT NULL,
    minimum_xp INT NOT NULL,
    PRIMARY KEY (rule_version, level_number),
    UNIQUE KEY uk_level_threshold (rule_version, minimum_xp)
) ENGINE=InnoDB;

INSERT INTO level_thresholds (rule_version, level_number, minimum_xp)
VALUES (1, 1, 0), (1, 2, 100), (1, 3, 250), (1, 4, 500), (1, 5, 1000);

CREATE VIEW coding_days AS
SELECT s.account_id, sub.provider, DATE(s.occurred_at) AS coding_date
FROM solves s JOIN submissions sub ON sub.id = s.submission_id
GROUP BY s.account_id, sub.provider, DATE(s.occurred_at);
