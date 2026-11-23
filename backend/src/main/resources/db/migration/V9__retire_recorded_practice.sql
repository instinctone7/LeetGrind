-- Keep original activity and free reward keys for future verified awards.
UPDATE xp_entries x
JOIN reward_windows w ON w.provider = 'LEETCODE' AND x.reward_key = CONCAT(w.kind, ':', w.id)
JOIN reward_evidence e ON e.account_id = x.account_id AND e.window_id = w.id
JOIN solves s ON s.id = e.solve_id
JOIN submissions sub ON sub.id = s.submission_id AND sub.provider = 'MANUAL'
SET x.provider = 'MANUAL', x.reward_key = CONCAT('RETIRED:', x.reward_key)
WHERE x.provider IN ('LEETCODE', 'MANUAL');

-- Restore a bonus when verified evidence alone satisfied the same window.
INSERT INTO xp_entries (account_id, solve_id, provider, reward_key, reason, amount, rule_version, effective_at, recorded_at)
WITH eligible AS (
    SELECT x.account_id, s.id AS solve_id, w.id AS window_id, w.kind, w.reward_xp, w.rule_version, w.target_count,
           s.occurred_at, GREATEST(s.recorded_at, x.recorded_at) AS recorded_at,
           ROW_NUMBER() OVER (PARTITION BY x.account_id, w.id ORDER BY s.id) AS evidence_order
    FROM xp_entries x
    JOIN reward_windows w ON w.provider = 'LEETCODE' AND x.reward_key = CONCAT('RETIRED:', w.kind, ':', w.id)
    JOIN reward_evidence e ON e.account_id = x.account_id AND e.window_id = w.id
    JOIN solves s ON s.id = e.solve_id
    JOIN submissions sub ON sub.id = s.submission_id AND sub.provider = 'LEETCODE'
    WHERE x.provider = 'MANUAL'
)
SELECT account_id, solve_id, 'LEETCODE', CONCAT(kind, ':', window_id), kind, reward_xp, rule_version, occurred_at, recorded_at
FROM eligible WHERE evidence_order = target_count;

WITH affected AS (
    SELECT DISTINCT account_id FROM submissions WHERE provider = 'MANUAL'
), verified_days AS (
    SELECT d.account_id, d.coding_date,
           LAG(d.coding_date, 2) OVER (PARTITION BY d.account_id ORDER BY d.coding_date) AS two_days_prior
    FROM coding_days d JOIN affected a ON a.account_id = d.account_id
    WHERE d.provider = 'LEETCODE'
), verified_badges AS (
    SELECT account_id, MAX(DATEDIFF(coding_date, two_days_prior) = 2) AS three_day_streak
    FROM verified_days GROUP BY account_id
)
UPDATE user_achievements a
JOIN affected legacy ON legacy.account_id = a.account_id
LEFT JOIN verified_badges v ON v.account_id = a.account_id
SET a.provider = 'MANUAL'
WHERE a.provider = 'LEETCODE' AND (
    (a.code = 'FIRST_SOLVE' AND v.account_id IS NULL)
    OR (a.code = 'THREE_DAY_STREAK' AND NOT COALESCE(v.three_day_streak, FALSE))
);

UPDATE notifications n
JOIN user_achievements a ON a.account_id = n.recipient_id AND a.provider = 'MANUAL'
    AND n.source_key = CONCAT('LEETCODE:', a.code)
SET n.source_key = CONCAT('RETIRED:', n.id)
WHERE n.kind = 'ACHIEVEMENT';

UPDATE notifications n
JOIN solves s ON s.account_id = n.recipient_id AND n.source_key = CONCAT(s.id, ':', n.group_id)
JOIN submissions sub ON sub.id = s.submission_id AND sub.provider = 'MANUAL'
SET n.source_key = CONCAT('RETIRED:', n.id)
WHERE n.kind = 'STANDINGS_CHANGE';
