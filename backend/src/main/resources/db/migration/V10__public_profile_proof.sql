ALTER TABLE leetcode_links ADD COLUMN ownership_code VARCHAR(40) NULL;

UPDATE leetcode_links
SET ownership_code = CONCAT('lg-', LOWER(HEX(RANDOM_BYTES(10))))
WHERE state = 'PENDING';
