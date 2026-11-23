# Phase 4 evaluation — Private-group MVP release

Use the [phase plan](../../phase-wise-implementation-plan.md#phase-4--harden-and-release-the-private-group-mvp) and [operations edge cases](../../edgecase.md#release-recovery-and-scaling).

## Checks

- [x] **E4.1 — Staging release (OP-02).** Deploy the frontend and API under one HTTPS origin. Apply the candidate migrations, exercise the documented rollback, and restore a backup into staging. Confirm application/schema compatibility and reconcile restored jobs, solves, and XP against the recovery point.
- [ ] **E4.2 — Live acceptance (LC-01, SY-03, GR-03, GR-05).** Run the two-account private group flow with approved verified LeetCode profiles. Confirm the accepted Medium solve yields exactly 25 XP, retries preserve it, and private standings cease to be accessible after removal. Run deterministic regression checks for week and membership boundaries separately.
- [x] **E4.3 — Degradation and recovery (LC-04, SY-06, OP-05).** Simulate provider errors, a stale job, and an interrupted worker. Confirm the expected diagnostic signals and safe owner-facing status. Pause, resume, and replay sync without losing or duplicating confirmed XP.
- [x] **E4.4 — Account deletion (OP-01).** Delete a test account with queued or in-flight work. Verify sessions and links are revoked, future sync stops, derived views remove personal data, and historical scores follow the account deletion policy.

## Evidence and exit gate

Attach the Phase 0 live-scoring decision, candidate version, staging logs, restore and rollback results, live acceptance run, and deletion evidence. Pass for a limited launch only when all checks pass. Review observed sync freshness, scoring accuracy, and privacy before widening access.

### Local staging run on 21 November 2026

The packaged candidate used OpenAPI `0.4.0`, Flyway V4, and image `sha256:18db189be8a61dd873a57e7fdb8aa3d0e408226d950ec31322bace6c030c7e7e` for the drill.

- **E4.1 — Pass locally.** A CA-verified HTTPS stage served both the frontend and API, issued a Secure, HttpOnly refresh cookie, and refused edge access to metrics. An existing Phase 3 fixture database migrated from V3 to V4. The previous app then ran against V4 with fixture processing and migration execution disabled. A new staging project restored the dump and passed the two-account check. Upgrade, rollback, and restore each retained one submission, one solve, 25 demo XP, one outbox event, and one job. The restore retained V4, the pause, job state, last attempt, and checkpoint. The dump had `0600` permissions. Attempts to overwrite the backup or restore into the populated schema were rejected.
- **E4.2 — Fixture pass, live blocked.** The [HTTPS smoke script](../../../scripts/phase4/https_smoke.py) checked the full two-account fixture journey. The [group integration tests](../../../backend/src/test/java/com/leetgrind/groups/PrivateGroupsIntegrationTest.java) checked duplicate invite redemption, membership timing and the Monday UTC boundary. The browser checked private access after removal. Approved live profiles and a supported integration remain outstanding under Phase 0.
- **E4.3 — Pass locally.** A paused and requeued job survived graceful app shutdown and restart. Resumption preserved the 25 XP award and recorded a deduplication. The staging metrics showed an HTTP `401`, a completed sync, and leaderboard timing. The [sync integration tests](../../../backend/src/test/java/com/leetgrind/integration/SubmissionSyncIntegrationTest.java) verified expired-lease recovery and measured throttle, malformed-response, and deduplication outcomes. The crash simulation covered rollback before commit and replay after commit.
- **E4.4 — Pass with synthetic data.** Password confirmation was enforced. Erasure removed the learner’s sessions, links, provider binding, leased job, practice data, outbox event, membership history, and scores. The stale worker and old access and refresh credentials could not write or authenticate. An owner with an open group was refused and could delete after closure.

All 23 MySQL integration tests and five browser tests passed. With pilot mode enabled on the restored project, an allowlisted fixture could register and an unlisted fixture was denied. No real participants were onboarded. Follow the [release runbook](../../phase4-release-runbook.md) to reproduce. Hosted CI verification and retention sign-off are pending.
