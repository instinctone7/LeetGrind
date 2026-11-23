-- Keep existing identity bindings. For a previously unbound handle, use the earliest saved link.
INSERT INTO provider_identities (provider, provider_account_id, account_id)
SELECT 'LEETCODE', pending.handle, pending.account_id
FROM leetcode_links pending
JOIN (
    SELECT MIN(id) AS id
    FROM leetcode_links
    WHERE state = 'PENDING'
    GROUP BY handle
) first_link ON first_link.id = pending.id
LEFT JOIN provider_identities identity
    ON identity.provider = 'LEETCODE' AND identity.provider_account_id = pending.handle
WHERE identity.account_id IS NULL;

UPDATE leetcode_links pending
JOIN provider_identities identity
    ON identity.provider = 'LEETCODE' AND identity.provider_account_id = pending.handle
    AND identity.account_id = pending.account_id
SET pending.state = 'VERIFIED',
    pending.provider = identity.provider,
    pending.provider_account_id = identity.provider_account_id,
    pending.verified_at = UTC_TIMESTAMP(6),
    pending.activated_at = UTC_TIMESTAMP(6)
WHERE pending.state = 'PENDING';
