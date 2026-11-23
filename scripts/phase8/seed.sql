SET @history_accounts = 600;
SET @burst_accounts = 150;
SET @days = 180;
SET @day = UTC_DATE();
UPDATE sync_control SET paused = TRUE WHERE id = 1;

CREATE TEMPORARY TABLE capacity_numbers (n INT PRIMARY KEY);
INSERT INTO capacity_numbers
WITH RECURSIVE numbers AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM numbers WHERE n < 750)
SELECT n FROM numbers;

INSERT INTO accounts (id, email, handle, display_name, password_hash, active)
SELECT 10000 + n, CONCAT('capacity-', n, '@example.test'), CONCAT('capacity_', n),
       CONCAT('Fixture learner ', n), 'synthetic-login-disabled', TRUE
FROM capacity_numbers WHERE n <= @history_accounts + @burst_accounts;

INSERT INTO memberships (group_id, account_id, joined_at, left_at)
SELECT @bench_group, 10000 + n, @day - INTERVAL 200 DAY,
       CASE WHEN MOD(n, 5) = 0 THEN @day - INTERVAL 90 DAY
            WHEN MOD(n, 7) = 0 THEN @day - INTERVAL 20 DAY ELSE NULL END
FROM capacity_numbers WHERE n <= @history_accounts;
INSERT INTO memberships (group_id, account_id, joined_at, left_at)
SELECT @bench_group, 10000 + n, @day - INTERVAL 75 DAY,
       CASE WHEN MOD(n, 7) = 0 THEN @day - INTERVAL 20 DAY ELSE NULL END
FROM capacity_numbers WHERE n <= @history_accounts AND MOD(n, 5) = 0;
INSERT INTO memberships (group_id, account_id, joined_at)
SELECT @bench_group, 10000 + n, @day - INTERVAL 1 DAY
FROM capacity_numbers WHERE n > @history_accounts AND n <= @history_accounts + 50;

INSERT INTO provider_identities (provider, provider_account_id, account_id)
SELECT 'FIXTURE', CONCAT('fixture-', 10000 + n), 10000 + n FROM capacity_numbers;

INSERT INTO leetcode_links (id, account_id, handle, state, provider, provider_account_id, verified_at, activated_at)
SELECT 10000 + n, 10000 + n, CONCAT('fixture-', 10000 + n),
       CASE WHEN n <= @history_accounts THEN 'DISCONNECTED' ELSE 'VERIFIED' END, 'FIXTURE',
       CONCAT('fixture-', 10000 + n), @day - INTERVAL 200 DAY, @day - INTERVAL 200 DAY
FROM capacity_numbers;
INSERT INTO sync_jobs (link_id, link_version, state, next_run_at)
SELECT id, version, 'QUEUED', UTC_TIMESTAMP(6) FROM leetcode_links WHERE state = 'VERIFIED';

INSERT INTO problems (id, provider, provider_problem_id, slug, title, difficulty)
SELECT 30000 + n, 'FIXTURE', CONCAT('capacity-', n), CONCAT('capacity-', n),
       CONCAT('Fixture problem ', n), ELT(1 + MOD(n, 3), 'EASY', 'MEDIUM', 'HARD')
FROM capacity_numbers WHERE n <= @days;

INSERT INTO submissions (account_id, link_id, provider, provider_account_id, provider_submission_id,
                         problem_id, outcome, difficulty, occurred_at, recorded_at)
SELECT l.account_id, l.id, 'FIXTURE', l.provider_account_id, p.provider_problem_id, p.id, 'ACCEPTED', p.difficulty,
       @day - INTERVAL (p.id - 30000) DAY + INTERVAL MOD(l.id, 1440) MINUTE,
       @day - INTERVAL (p.id - 30000) DAY + INTERVAL MOD(l.id, 1440) MINUTE + INTERVAL 3 MINUTE
FROM leetcode_links l CROSS JOIN problems p
WHERE l.state = 'DISCONNECTED' AND p.id BETWEEN 30001 AND 30180;

INSERT INTO solves (account_id, problem_id, submission_id, difficulty, occurred_at, recorded_at)
SELECT account_id, problem_id, id, difficulty, occurred_at, recorded_at FROM submissions;
INSERT INTO xp_entries (account_id, solve_id, provider, reward_key, reason, amount, rule_version, effective_at, recorded_at)
SELECT account_id, id, 'FIXTURE', CONCAT('SOLVE:', problem_id), 'BASE_SOLVE',
       CASE difficulty WHEN 'EASY' THEN 10 WHEN 'MEDIUM' THEN 25 ELSE 50 END, 1, occurred_at, recorded_at
FROM solves;
INSERT INTO xp_entries (account_id, solve_id, provider, reward_key, reason, amount, rule_version, effective_at, recorded_at)
SELECT account_id, id, 'FIXTURE', CONCAT('CAPACITY:', problem_id), 'FEATURED', 50, 1, occurred_at, recorded_at
FROM solves WHERE MOD(problem_id, 5) = 0;

ANALYZE TABLE memberships, xp_entries, sync_jobs;
