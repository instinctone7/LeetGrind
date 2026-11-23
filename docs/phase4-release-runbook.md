# Phase 4 release and recovery runbook

Use the [Phase 4 evaluation](evaluation/phase-04/eval.md) and the [Phase 0 integration decision](evaluation/phase-00/evidence.md) as release gates. The Phase 4 Compose setup is for loopback staging with synthetic accounts. The same HTTPS origin serves the React app and REST API. Only the TLS edge port is published. Application, database, and metrics access stays on the container side.

## Start HTTPS staging

Create the local environment from the [setup instructions](../README.md#run-locally) if needed. Set a unique project name, an image tag for the candidate, and explicit staging values. Open registration in this example is for fixture-only validation.

```sh
export COMPOSE_PROJECT_NAME=leetgrind-staging
export LEETGRIND_APP_IMAGE=leetgrind-staging-app:candidate
export LEETGRIND_SIGNUP_MODE=OPEN
export LEETGRIND_FIXTURES_ENABLED=true

docker compose -f compose.yaml -f compose.staging.yaml up --build -d --wait
docker compose -f compose.yaml -f compose.staging.yaml exec -T edge \
  cat /data/caddy/pki/authorities/local/root.crt > /tmp/leetgrind-staging-ca.crt
curl --cacert /tmp/leetgrind-staging-ca.crt --fail \
  https://127.0.0.1:8443/actuator/health/readiness
```

The TLS edge uses a local Caddy CA. Use its root certificate explicitly for local checks, with TLS verification enabled. For a hosted deployment, use the deployment’s HTTPS origin and a certificate trusted by the intended clients. The default HTTPS Compose registration mode is `CLOSED`. [Caddy TLS configuration](https://caddyserver.com/docs/caddyfile/directives/tls)

The fixture acceptance script creates only synthetic local accounts. Set `--state` to a new file in a protected directory. Its `check` action verifies those same accounts and scores after a restart, upgrade, or rollback.

```sh
python3 -B scripts/phase4/https_smoke.py seed --origin https://127.0.0.1:8443 \
  --ca /tmp/leetgrind-staging-ca.crt --state /tmp/leetgrind-staging-journey.json
```

## Diagnose and recover sync

Run the operator script from the staging host with the same Compose project and environment. The commands use the private DB or app process. User requests cannot reach them. The SQL snapshot shows oldest verified-link sync age, expired leases, and job counts and safe failure codes. The `sync_backlog` row contains the number of due jobs, including expired leases, and the oldest due age in seconds. Actuator records HTTP errors through response status, sync outcomes by result, expired lease recoveries, XP deduplications, and leaderboard query latency. The metrics have no account identifiers, provider handles, or credentials in their tags. [Spring Boot Actuator endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)

```sh
scripts/phase4/staging-ops.sh status
scripts/phase4/staging-ops.sh metrics http.server.requests
scripts/phase4/staging-ops.sh metrics leetgrind.sync.outcomes
scripts/phase4/staging-ops.sh metrics leetgrind.sync.provider.fetch.duration
scripts/phase4/staging-ops.sh metrics leetgrind.sync.queue.wait.duration
scripts/phase4/staging-ops.sh metrics leetgrind.sync.expired.leases
scripts/phase4/staging-ops.sh metrics leetgrind.xp.deduplications
scripts/phase4/staging-ops.sh metrics leetgrind.leaderboard.query.duration
```

Some meters first appear when the corresponding work runs. `THROTTLED` identifies rate-limited provider attempts. Check the job’s failure code and any requested retry delay before requeuing. For a storage failure, check app logs using the correlation ID or internal job ID and let the lease expire if the worker cannot release it.

The fetch timer counts adapter calls, including failed attempts. The current fixture adapter makes zero external requests. Queue wait runs from a job’s due time or lease expiry to a successful claim. Use the fresh-fixture capacity workload to compare these signals with HTTP latency and MySQL load before changing the deployment. [Phase 8 evaluation](evaluation/phase-08/eval.md)

```sh
scripts/phase4/staging-ops.sh pause
scripts/phase4/staging-ops.sh status
scripts/phase4/staging-ops.sh replay 123
scripts/phase4/staging-ops.sh resume
```

Pause stops new claims and lets committed leases finish. Replay accepts a current verified, unleased job in an idle, failed-retry, or paused state while the global pause is active. It preserves the successful checkpoint and existing awards. Under graceful shutdown, the executor has time to finish. A replacement worker reclaims abandoned jobs after their existing lease period.

## Back up, restore, and roll back

1. Record the candidate and previous image tags, migration version, and recovery point. Pause sync and wait for running work to settle before taking a release checkpoint. Avoid running schema changes while the dump is in progress.
2. Run `scripts/phase4/staging-ops.sh backup <new-file>`. The transactional MySQL dump includes Flyway history. The script creates a restricted-permission file and refuses to overwrite an existing path. Store backups in restricted storage. [MySQL mysqldump documentation](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html)
3. Restore into an isolated, new Compose project after starting only its database. Run `scripts/phase4/staging-ops.sh restore-empty <backup-file>`. Compare the job states, submission and solve counts, XP total, outbox count, and migration version with the release checkpoint. Run the HTTPS fixture check against the restored database using the restored project’s CA.
4. To rehearse app rollback, retain the candidate schema and switch to the last compatible image. Stop or disable old sync processing because the Phase 3 worker predates the shared pause control. Disable migration execution for the rollback app, then run the HTTPS fixture check. Restore the candidate app and normal migration setting before resuming work.

```sh
LEETGRIND_APP_IMAGE=leetgrind-staging-app:previous \
LEETGRIND_MIGRATIONS_ENABLED=false LEETGRIND_SCHEDULER_ENABLED=false \
LEETGRIND_FIXTURES_ENABLED=false \
  docker compose -f compose.yaml -f compose.staging.yaml up -d --no-build --wait app
```

Use the smoke script’s `rollback` action after this switch. It verifies existing accounts, personal credits, and the live standings with fixture processing disabled. Restart the edge if it retains an upstream connection to the previous container.

## Account retention and pilot policy

Account deletion requires a signed-in user and current password. The account must transfer or close all open groups it owns. Deletion removes credentials and refresh sessions, links and provider identity bindings, sync jobs, submissions, solves, XP entries, outbox events related to those solves, and the learner’s membership history. The learner’s entries disappear from current and historical standings. Shared groups and other members’ scores remain. Closed groups retain their metadata with the former owner reference cleared.

The proposed retention period for restricted encrypted backups is seven days. A restored snapshot can contain data erased after its recovery point. Keep restores isolated until post-backup erasures are reconciled against an independently maintained operator deletion record. Backup retention and the deleted-account restore procedure must be accepted before admitting real accounts. The local fixture restore exercises the database mechanics without real personal data.

Pilots use `LEETGRIND_SIGNUP_MODE=PILOT` and `LEETGRIND_PILOT_EMAILS` with the approved emails. Use the allowlist behind restricted participant access because registering an approved email does not prove control of that address. Keep registration closed and the live adapter disabled while access, identity verification, and accepted submission evidence remain unresolved. After a real pilot is authorized, review sync freshness against the approved provider cadence, confirm each reference submission earns the expected XP once, and check that outsiders and removed members cannot read standings before expanding access. [Phase 0 evidence](evaluation/phase-00/evidence.md)
