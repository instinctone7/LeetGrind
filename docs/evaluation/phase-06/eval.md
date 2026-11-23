# Phase 6 evaluation — Expanded group competition

Use the [phase plan](../../phase-wise-implementation-plan.md#phase-6--expand-group-competition), [group edge cases](../../edgecase.md#membership-privacy-and-standings), and [engagement edge cases](../../edgecase.md#engagement-and-competition).

## Checks

- [x] **E6.1 — Periods and visibility (GR-02, GR-03, GR-04, GR-05).** Test activity on each side of a UTC month boundary and across a leave–rejoin interval. Weekly, monthly, and all-time totals include only eligible intervals. Verify the signed-in public preview and require a separate participation decision before enabling a platform-wide leaderboard.
- [x] **E6.2 — Challenge and duel lifecycle (EN-04).** Create a group challenge and a duel, enroll participants, and advance the clock through start and deadline. Verify target-problem, distinct-count, and difficulty rules. Reject evidence outside the challenge, enrollment, or required membership window.
- [x] **E6.3 — Finalization and late activity (EN-05).** Replay challenge evidence and delay projections. Confirm that finalization reconciles persisted solves, respects the 24-hour grace window and shared-tie policy, and writes one final result. Re-running finalization leaves the recorded result unchanged.
- [x] **E6.4 — Feed and notifications (EN-06, GR-05).** Replay an invitation or completion event. Each intended recipient receives one notification. Remove a member and verify an old notification or feed link cannot reveal private content.

## Evidence and exit gate

Attach the expected and actual period totals, published challenge rules, timed fixture and final result, and notification authorization checks. Pass when the private-group regression suite and all enabled competition checks pass.

### Local fixture run on 21 November 2026

The candidate uses OpenAPI `0.6.0`, Flyway V6, and competition rules v1. All 32 MySQL integration tests, six browser tests, and three offline Phase 0 evaluator tests passed. The frontend production build passed.

- **E6.1 — Pass.** The month-boundary fixture gave the learner 50 XP in January, 50 XP in February, and 100 XP all time. The weeks beginning 26 January and 2 February returned 75 XP and 25 XP. Exact joins, departures, the membership gap, and personal bonuses contributed zero. Other periods rejected a cursor from January. Signed-in viewers saw only public directory previews until they joined, and a repeated join created one membership. [Group integration tests](../../../backend/src/test/java/com/leetgrind/groups/PrivateGroupsIntegrationTest.java) · [Group browser tests](../../../frontend/tests/groups.spec.ts)
- **E6.2 — Pass.** The fixture created a group challenge with a distinct-solve target of two, a Medium-only goal, and an invite-only duel for a specific problem. Enrollment was idempotent. A pre-acceptance solve scored zero in the duel, and the enrolled opponent’s matching solve scored one. A Hard solve contributed zero to the Medium goal. The browser completed public discovery, enrollment through the invitation, and demo solve progress. [Group integration tests](../../../backend/src/test/java/com/leetgrind/groups/PrivateGroupsIntegrationTest.java) · [Group browser tests](../../../frontend/tests/groups.spec.ts)
- **E6.3 — Pass.** With the rewards projection disabled, a solve recorded one microsecond before the cutoff counted. A solve recorded exactly at cutoff and a solve at the event deadline both scored zero. Racing finalizers produced one stored `2–2` tie and four evidence rows. A later import preserved the recorded outcome. On the packaged app, advancing a disposable duel into the due queue produced one result, one evidence row, and one result notice for the winning learner. Restarting preserved the result and notice timestamps. Erasing that account removed its evidence, participant row, and notices while preserving the recorded outcome. [Group integration tests](../../../backend/src/test/java/com/leetgrind/groups/PrivateGroupsIntegrationTest.java)
- **E6.4 — Pass.** Invitation replay returned the original notice, and repeating a read preserved its timestamp. The solve outbox replay preserved the achievement and each group XP notification exactly once. A removed learner received `404` on the old notice, duel, and activity endpoints, and the inbox hid the group notices. A third group member could not see the duel. [Group integration tests](../../../backend/src/test/java/com/leetgrind/groups/PrivateGroupsIntegrationTest.java) · [Reward consumer replay test](../../../backend/src/test/java/com/leetgrind/engagement/PracticeRewardsIntegrationTest.java)

Local fixture exit criteria passed. Platform-wide rankings still require a participation and visibility decision. Live scoring depends on the Phase 0 provider and ownership checks. Hosted CI verification is pending. [Phase 0 integration decision](../phase-00/evidence.md)
