ALTER TABLE submissions MODIFY link_id BIGINT NULL,
    ADD CONSTRAINT ck_submission_link CHECK (
        (provider = 'MANUAL' AND link_id IS NULL) OR (provider <> 'MANUAL' AND link_id IS NOT NULL)
    );
