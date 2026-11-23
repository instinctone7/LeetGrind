# Phase 2 evaluation — Profile linking, sync, and XP

Use the [phase plan](../../phase-wise-implementation-plan.md#phase-2--link-profiles-ingest-submissions-and-credit-xp), [provider edge cases](../../edgecase.md#provider-identity-and-data-coverage), and [sync edge cases](../../edgecase.md#sync-transactions-and-xp).

## Checks

- [x] **E2.1 — Link states (LC-01, LC-02, SY-02).** Exercise pending, verified, disconnected, and relinked states. Confirm a pending link earns no XP and duplicate verification cannot bind another account. Pause a worker after fetch, disconnect or replace the link, and resume it. The stale worker writes no credit and cannot advance the new checkpoint.
- [x] **E2.2 — Award rules (LC-03, LC-05, LC-06, SY-04, SY-05).** Import three distinct qualifying solves, one per difficulty. Expect exactly three solve rows, three base entries for 10, 25, and 50 XP, three corresponding outbox events, and a total of 85 XP. Replay the payload and add a second accepted submission for the Medium problem. The total remains 85 XP. Incomplete, rejected, and ineligible submissions earn zero.
- [x] **E2.3 — Work ownership and atomicity (SY-01, SY-03).** Race scheduled and manual sync requests, then reclaim an expired lease. Reject stale commits. Interrupt the database write before commit and verify no partial solve, XP, or outbox rows. Interrupt immediately after commit and retry. The committed award remains singular.
- [x] **E2.4 — Sync state (LC-04, SY-06).** After one known successful sync, induce a partial response and then a provider failure. Preserve prior credit and last successful sync, update last attempt, and show safe owner-visible failure or coverage status. Complete a later fetch and verify that the success checkpoint advances once.
- [x] **E2.5 — Award attribution (LC-06, SY-05).** Import a pre-activation solve and an older submission for an already credited problem. Verify the previously approved history and late-discovery rules. If reward corrections ship, apply the same correction twice and confirm one compensating entry, the original effective time, and no mutation of the original award.

## Evidence and exit gate

Attach MySQL integration results, before-and-after row counts, sync status responses, and the test clock and fixtures used. Pass internal delivery on fixture evidence. Live scoring remains gated by the Phase 0 decision and a successful verified-profile sync.

### Local run on 21 November 2026

- **E2.1 — Pass with fixtures.** With demo mode disabled, saving a fixture-shaped handle left it pending and created no job. The fixture-enabled tests verified a unique provider binding, link history, owner-only access, and stale-job rejection after replacement or disconnect.
- **E2.2 — Pass.** The MySQL test recorded three solves, three awards totaling 85 demo XP, and three outbox events. Replay, repeat solves, rejected submissions and pre-activation evidence added zero XP. The browser showed 25 demo XP separately from live XP and retained that total after relinking.
- **E2.3 — Pass.** Manual and scheduled work shared one job. Reclaiming an expired lease rejected the old worker. The rollback test left all four write tables empty for the account, and replay committed the award once. A concurrent completion and disconnect left a consistent ledger and outbox.
- **E2.4 — Pass with fixtures.** A partial result credited its one valid new solve and kept the successful checkpoint. The throttling fixture delayed the next attempt by 90 seconds. A complete retry advanced the checkpoint without duplicate credit.
- **E2.5 — Pass.** The earlier eligible evidence retained the existing award's effective time. Pre-activation evidence earned no XP. Reward corrections are outside this phase.

The [MySQL evaluation](../../../backend/src/test/java/com/leetgrind/integration/SubmissionSyncIntegrationTest.java) uses a fixed UTC clock starting at 10:00 on 5 January 2026. The [browser regression](../../../frontend/tests/practice.spec.ts) uses the actual scheduler in the fixture-enabled Docker build. Reproduce it with the [local instructions](../../../README.md#try-a-fixture-sync). Hosted CI verification is pending.

**Live scoring — Blocked.** The app has no live provider adapter and the fixture adapter makes no network requests. Enable live scoring only after the [Phase 0 requirements](../phase-00/evidence.md) for supported access, verified profile ownership, and submission evidence are satisfied.
