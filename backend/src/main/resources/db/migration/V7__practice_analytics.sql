CREATE TABLE practice_catalog (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(16) NOT NULL,
    slug VARCHAR(128) NOT NULL,
    title VARCHAR(160) NOT NULL,
    difficulty VARCHAR(8) NOT NULL,
    UNIQUE KEY uk_curated_problem (provider, slug),
    CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE practice_catalog_topics (
    catalog_id BIGINT NOT NULL,
    topic VARCHAR(40) NOT NULL,
    PRIMARY KEY (catalog_id, topic),
    FOREIGN KEY (catalog_id) REFERENCES practice_catalog (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO practice_catalog (provider, slug, title, difficulty) VALUES
    ('LEETCODE', 'two-sum', 'Two Sum', 'EASY'),
    ('LEETCODE', 'binary-search', 'Binary Search', 'EASY'),
    ('LEETCODE', 'best-time-to-buy-and-sell-stock', 'Best Time to Buy and Sell Stock', 'EASY'),
    ('LEETCODE', 'valid-parentheses', 'Valid Parentheses', 'EASY'),
    ('LEETCODE', 'valid-anagram', 'Valid Anagram', 'EASY'),
    ('LEETCODE', 'invert-binary-tree', 'Invert Binary Tree', 'EASY'),
    ('LEETCODE', 'merge-intervals', 'Merge Intervals', 'MEDIUM'),
    ('LEETCODE', 'number-of-islands', 'Number of Islands', 'MEDIUM'),
    ('LEETCODE', 'longest-substring-without-repeating-characters', 'Longest Substring Without Repeating Characters', 'MEDIUM'),
    ('LEETCODE', 'trapping-rain-water', 'Trapping Rain Water', 'HARD'),
    ('FIXTURE', 'fixture-array-window', 'Array Window', 'MEDIUM'),
    ('FIXTURE', 'fixture-stack', 'Stack Warmup', 'EASY'),
    ('FIXTURE', 'fixture-hash', 'Hash Lookup', 'EASY'),
    ('FIXTURE', 'fixture-tree', 'Tree Traversal', 'MEDIUM'),
    ('FIXTURE', 'fixture-graph', 'Graph Routes', 'HARD');

INSERT INTO practice_catalog_topics (catalog_id, topic)
SELECT c.id, topic.name
FROM practice_catalog c
CROSS JOIN JSON_TABLE(
    CASE c.slug
        WHEN 'two-sum' THEN '["Array","Hash Table"]'
        WHEN 'binary-search' THEN '["Array","Binary Search"]'
        WHEN 'best-time-to-buy-and-sell-stock' THEN '["Array","Dynamic Programming"]'
        WHEN 'valid-parentheses' THEN '["Stack","String"]'
        WHEN 'valid-anagram' THEN '["Hash Table","String","Sorting"]'
        WHEN 'invert-binary-tree' THEN '["Tree","Depth-First Search","Breadth-First Search"]'
        WHEN 'merge-intervals' THEN '["Array","Sorting"]'
        WHEN 'number-of-islands' THEN '["Matrix","Depth-First Search","Breadth-First Search"]'
        WHEN 'longest-substring-without-repeating-characters' THEN '["String","Hash Table","Sliding Window"]'
        WHEN 'trapping-rain-water' THEN '["Array","Two Pointers"]'
        WHEN 'fixture-array-window' THEN '["Array","Sliding Window"]'
        WHEN 'fixture-stack' THEN '["Stack"]'
        WHEN 'fixture-hash' THEN '["Array","Hash Table"]'
        WHEN 'fixture-tree' THEN '["Tree"]'
        WHEN 'fixture-graph' THEN '["Graph","Breadth-First Search"]'
        ELSE '[]'
    END, '$[*]' COLUMNS (name VARCHAR(40) PATH '$')
) AS topic;

ALTER TABLE problems ADD COLUMN practice_catalog_id BIGINT,
    ADD FOREIGN KEY (practice_catalog_id) REFERENCES practice_catalog (id) ON DELETE SET NULL;

UPDATE problems p JOIN practice_catalog c ON p.provider = c.provider AND p.slug = c.slug
SET p.practice_catalog_id = c.id;

-- A credited solve can preserve the old slug after the provider changes it.
UPDATE problems p
JOIN (
    SELECT s.problem_id, MIN(c.id) AS catalog_id
    FROM outbox_events o JOIN solves s ON s.id = o.aggregate_id
    JOIN submissions sub ON sub.id = s.submission_id
    JOIN practice_catalog c ON c.provider = sub.provider
        AND c.slug = JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.problemSlug'))
    WHERE o.event_type = 'SolveCredited' AND o.event_version = 1
    GROUP BY s.problem_id HAVING MIN(c.id) = MAX(c.id)
) AS known ON known.problem_id = p.id
SET p.practice_catalog_id = known.catalog_id
WHERE p.practice_catalog_id IS NULL;
