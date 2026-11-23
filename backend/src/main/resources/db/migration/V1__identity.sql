CREATE TABLE accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) NOT NULL,
    handle VARCHAR(32) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_account_email UNIQUE (email),
    CONSTRAINT uk_account_handle UNIQUE (handle)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE session_families (
    id VARCHAR(36) NOT NULL,
    account_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX ix_session_account (account_id),
    CONSTRAINT fk_session_account FOREIGN KEY (account_id) REFERENCES accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE refresh_sessions (
    digest VARCHAR(64) NOT NULL,
    family_id VARCHAR(36) NOT NULL,
    used_at DATETIME(6),
    PRIMARY KEY (digest),
    INDEX ix_refresh_family (family_id),
    CONSTRAINT fk_refresh_family FOREIGN KEY (family_id) REFERENCES session_families (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
