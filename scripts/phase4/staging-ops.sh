#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."
: "${COMPOSE_PROJECT_NAME:?Set COMPOSE_PROJECT_NAME to the intended staging project}"

compose() {
    docker compose -f compose.yaml -f compose.staging.yaml "$@"
}

mysql() {
    compose exec -T db sh -ec 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --batch --skip-column-names -u "$MYSQL_USER" "$MYSQL_DATABASE"'
}

usage() {
    echo 'Usage: staging-ops.sh {pause|resume|status|metrics [meter]|replay LINK_ID|backup PATH|restore-empty PATH}' >&2
    exit 2
}

case "${1:-}" in
    pause|resume)
        [[ $# == 1 ]] || usage
        paused=FALSE
        if [[ "$1" == pause ]]; then paused=TRUE; fi
        printf 'UPDATE sync_control SET paused = %s WHERE id = 1; SELECT paused FROM sync_control WHERE id = 1;\n' "$paused" | mysql
        ;;
    replay)
        [[ $# == 2 && "$2" =~ ^[1-9][0-9]{0,17}$ ]] || usage
        result=$(mysql <<SQL
UPDATE sync_jobs j JOIN leetcode_links l ON j.link_id = l.id AND j.link_version = l.version
JOIN sync_control c ON c.id = 1 AND c.paused = TRUE
SET j.state = 'QUEUED', j.next_run_at = UTC_TIMESTAMP(6), j.attempts = 0,
    j.lease_token = NULL, j.lease_expires_at = NULL
WHERE l.id = $2 AND l.state = 'VERIFIED' AND j.state IN ('IDLE', 'RETRY', 'PAUSED');
SELECT ROW_COUNT();
SQL
)
        if [[ "$result" != 1 ]]; then
            echo 'Replay requires paused sync and one active, unleased job.' >&2
            exit 1
        fi
        echo 'Requeued one job. Resume sync when ready.'
        ;;
    status)
        [[ $# == 1 ]] || usage
        mysql <<'SQL'
SELECT 'sync_paused', paused FROM sync_control WHERE id = 1;
SELECT 'oldest_sync_age_seconds', COALESCE(MAX(TIMESTAMPDIFF(SECOND,
    COALESCE(last_success_at, activated_at), UTC_TIMESTAMP(6))), 0)
FROM leetcode_links WHERE state = 'VERIFIED';
SELECT 'expired_leases', COUNT(*) FROM sync_jobs WHERE state = 'RUNNING' AND lease_expires_at <= UTC_TIMESTAMP(6);
SELECT 'sync_backlog', COUNT(*), COALESCE(MAX(TIMESTAMPDIFF(SECOND, ready_at, UTC_TIMESTAMP(6))), 0)
FROM (
    SELECT CASE WHEN state = 'RUNNING' THEN lease_expires_at ELSE next_run_at END AS ready_at
    FROM sync_jobs
    WHERE state IN ('QUEUED', 'IDLE', 'RETRY') OR state = 'RUNNING'
) due WHERE ready_at <= UTC_TIMESTAMP(6);
SELECT state, COALESCE(failure_code, '-'), COUNT(*) FROM sync_jobs GROUP BY state, failure_code;
SELECT j.link_id, j.state, COALESCE(j.failure_code, '-'), j.last_attempt_at
FROM sync_jobs j JOIN leetcode_links l ON l.id = j.link_id AND l.version = j.link_version
WHERE l.state = 'VERIFIED' AND (j.failure_code IS NOT NULL OR
    (j.state = 'RUNNING' AND j.lease_expires_at <= UTC_TIMESTAMP(6)))
ORDER BY j.last_attempt_at DESC LIMIT 20;
SQL
        ;;
    metrics)
        [[ $# -le 2 ]] || usage
        meter=${2:-}
        [[ -z "$meter" || "$meter" =~ ^[a-zA-Z0-9_.-]+$ ]] || usage
        compose exec -T app curl --fail --silent --show-error -G "http://127.0.0.1:8080/actuator/metrics${meter:+/$meter}" < /dev/null
        printf '\n'
        ;;
    backup)
        [[ $# == 2 ]] || usage
        if [[ -e "$2" || -L "$2" ]]; then
            echo 'Choose a new backup file.' >&2
            exit 1
        fi
        umask 077
        temp=$(mktemp "$2.XXXXXX")
        trap 'rm -f -- "$temp"' EXIT
        compose exec -T db sh -ec 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysqldump --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-add-drop-table --skip-lock-tables -u "$MYSQL_USER" "$MYSQL_DATABASE"' < /dev/null > "$temp"
        [[ -s "$temp" ]]
        ln "$temp" "$2"
        ;;
    restore-empty)
        [[ $# == 2 && -s "$2" ]] || usage
        if [[ -n "$(compose ps --status running -q app edge)" ]]; then
            echo 'Run the restore in an isolated staging project with only the database started.' >&2
            exit 1
        fi
        tables=$(printf 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();\n' | mysql)
        if [[ "$tables" != 0 ]]; then
            echo 'The restore target must be empty.' >&2
            exit 1
        fi
        mysql < "$2"
        ;;
    *) usage ;;
esac
