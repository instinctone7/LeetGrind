ALTER TABLE challenges
    ADD COLUMN duel_minutes INT NULL,
    ADD COLUMN duel_stake INT NULL,
    ADD COLUMN duel_problem_title VARCHAR(160) NULL,
    ADD CHECK ((duel_minutes IS NULL AND duel_stake IS NULL) OR
        (kind = 'DUEL' AND rule = 'PROBLEM' AND duel_minutes BETWEEN 10 AND 120
         AND duel_stake IN (5, 25, 50, 100) AND duel_problem_title IS NOT NULL));

ALTER TABLE challenge_participants ADD COLUMN xp_delta INT NULL,
    ADD INDEX ix_duel_reserved (account_id, challenge_id, enrolled_at);
