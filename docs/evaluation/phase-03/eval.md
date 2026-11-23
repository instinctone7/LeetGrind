# Phase 3 evaluation — Private groups and weekly standings

Use the [phase plan](../../phase-wise-implementation-plan.md#phase-3--deliver-private-groups-and-weekly-standings) and [group edge cases](../../edgecase.md#membership-privacy-and-standings).

## Checks

- [x] **E3.1 — Two-account workflow (GR-02, GR-05).** An owner creates a private group and a second verified learner joins by invite. Sync a qualifying Medium solve after joining. The learner sees one 25 XP award in personal progress and the weekly standings. Retry the sync, refresh the browser, and verify both totals are unchanged.
- [x] **E3.2 — Invitations and owner actions (GR-01, GR-06).** Race two accounts for the last use of an invite. Confirm one new membership and one redemption. Test an existing member’s retry, expired and revoked invites, and an owner attempting to leave before transferring ownership or closing the group.
- [x] **E3.3 — Membership and week boundaries (GR-02, GR-03).** Using the registry’s fixed clock, submit distinct Medium problems just before joining, exactly at join, leave, and rejoin, and during the membership gap. Each adds zero group XP. Submit three more at 10:05 and 12:05 UTC on 5 January and 23:59 UTC on 11 January. Expect 75 group XP for the original week even if all three are synced on 12 January. A separate solve exactly at 00:00 UTC on 12 January adds 25 XP to the new week.
- [x] **E3.4 — Ranking and privacy (GR-04, GR-05).** Put tied scores across a page boundary and verify a shared dense rank and stable ordering. Reject a cursor bound to another period or group. Deny private reads to outsiders and former members. Compare the payload against the approved public profile fields to ensure provider handles and sync details are absent.

## Evidence and exit gate

Attach the browser run, group API responses, and fixture-based membership and ranking assertions. Pass when the two-account journey and MySQL-backed privacy, concurrency, and time-boundary checks pass.

### Local run on 21 November 2026

All 21 MySQL integration tests and four browser tests passed, including the existing identity and sync checks.

- **E3.1 — Pass with fixtures.** The [two-account browser test](../../../frontend/tests/groups.spec.ts) observed 25 demo XP in personal progress and in the owner’s group view. Relinking and syncing again, then refreshing the browser, preserved the score. Live standings showed zero for the same learner.
- **E3.2 — Pass.** The [MySQL group suite](../../../backend/src/test/java/com/leetgrind/groups/PrivateGroupsIntegrationTest.java) observed one membership and one redemption for a two-account race on the last use. A member retry preserved that count. The suite also checked revoked and expired codes, owner permissions, transfer, and closure.
- **E3.3 — Pass.** The fixed UTC fixture produced 75 XP for the original week and 25 for the next. Solves at join, leave, and rejoin were excluded, and earlier points remained visible to the owner after the learner left.
- **E3.4 — Pass.** Equal scores shared rank across pages. The API rejected cross-group, cross-week, and cross-view cursors. Private endpoints returned `404` after removal and to outsiders. The roster and standings included only the documented group and LeetGrind identity fields. The removed learner’s browser cleared the protected view on refresh.

Reproduce using the [two-account setup](../../../README.md#try-a-private-group-with-two-accounts). Live provider verification remains gated by [Phase 0](../phase-00/evidence.md). Hosted CI verification is pending.
