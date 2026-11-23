ALTER TABLE study_groups
    DROP FOREIGN KEY study_groups_ibfk_1,
    MODIFY owner_id BIGINT NULL,
    ADD CONSTRAINT fk_study_group_owner FOREIGN KEY (owner_id) REFERENCES accounts (id) ON DELETE SET NULL;

CREATE TABLE sync_control (
    id TINYINT NOT NULL PRIMARY KEY,
    paused BOOLEAN NOT NULL,
    CHECK (id = 1)
) ENGINE=InnoDB;

INSERT INTO sync_control (id, paused) VALUES (1, FALSE);
