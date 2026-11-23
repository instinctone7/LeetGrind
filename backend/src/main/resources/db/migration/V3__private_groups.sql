CREATE TABLE study_groups (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(280) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6),
    PRIMARY KEY (id),
    FOREIGN KEY (owner_id) REFERENCES accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE memberships (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    left_at DATETIME(6),
    active_account_id BIGINT GENERATED ALWAYS AS (
        CASE WHEN left_at IS NULL THEN account_id ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_active_member (group_id, active_account_id),
    KEY ix_member_groups (active_account_id, group_id),
    KEY ix_member_intervals (group_id, account_id, joined_at, left_at),
    FOREIGN KEY (group_id) REFERENCES study_groups (id),
    FOREIGN KEY (account_id) REFERENCES accounts (id),
    CHECK (left_at IS NULL OR left_at >= joined_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE group_invites (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    code_digest VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    max_uses INT NOT NULL,
    uses INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_invite_digest (code_digest),
    KEY ix_group_invites (group_id, id),
    FOREIGN KEY (group_id) REFERENCES study_groups (id),
    CHECK (max_uses BETWEEN 1 AND 100 AND uses BETWEEN 0 AND max_uses)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
