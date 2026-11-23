# Phase 5 evaluation — Recurring practice and personal rewards

Use the [phase plan](../../phase-wise-implementation-plan.md#phase-5--add-recurring-practice-and-personal-rewards) and [engagement edge cases](../../edgecase.md#engagement-and-competition).

## Checks

- [x] **E5.1 — Coding days and streaks (EN-01).** Record several solves on one UTC day, then enter a later solve with a one-day gap. Confirm the first day counts once. Ingest a qualifying solve for the missing day and check the rebuilt calendar, current streak, and longest streak against expected dates. Repeated logins on a qualifying UTC day award 5 XP once and do not create a coding day.
- [x] **E5.2 — Featured problems and quests (EN-02).** Test eligible solves exactly at the start and end of the scoring window. Test delayed in-window solves on both sides of the configured ingestion cutoff. Verify displayed individual and group completion and the published handling of closed instances.
- [x] **E5.3 — Rewards and replays (EN-03, GR-06).** Complete the featured problem, a daily quest, and a weekly quest. Expect separate bonuses of +50, +30, and +100 XP. Repeat deliveries and restart the consumer after commit. Each reward and achievement appears once. Check a level transition and verify the personal bonuses leave base-only group standings unchanged.

## Evidence and exit gate

Attach the rule versions, UTC fixture timeline, ledger entries, consumer replay results, and rendered progress views. Pass when streaks and quests follow the published rules and each reward has one durable entry.

### Local fixture run on 21 November 2026

The candidate used OpenAPI `0.5.0`, Flyway V5, and engagement rules v1. All 28 MySQL integration tests, five browser tests, and three offline Phase 0 evaluator tests passed. The frontend production build passed.

- **E5.1 — Pass.** Two solves on 5 January and one on 7 January 2026 produced two coding dates and a one-day current streak. A delayed 6 January solve produced current and longest streaks of three days and unlocked the three-day streak badge once. After two inactive UTC days, the current streak expired and the longest stayed at three. Repeated sign-in and next-day refresh yielded 5 XP and zero coding days. Signing in on the next UTC day brought the login total to 10 XP. [Rewards integration tests](../../../backend/src/test/java/com/leetgrind/engagement/PracticeRewardsIntegrationTest.java) · [Login and erasure integration tests](../../../backend/src/test/java/com/leetgrind/integration/SubmissionSyncIntegrationTest.java)
- **E5.2 — Pass.** The daily fixture had a 5 January start, 6 January close, and 7 January cutoff, all at `00:00 UTC`. A solve at the opening counted, and a solve at the close entered the next window. An in-window solve recorded one microsecond before cutoff counted. At cutoff it earned only base XP. The weekly fixture repeated the cutoff check and assigned a solve at Monday `00:00 UTC` to the new week. Practice showed the eligible Array problems and quest state. The group view counted one of two members in the browser and excluded a pre-join solve in the boundary fixture. [Rewards integration tests](../../../backend/src/test/java/com/leetgrind/engagement/PracticeRewardsIntegrationTest.java) · [Practice browser test](../../../frontend/tests/practice.spec.ts) · [Groups browser test](../../../frontend/tests/groups.spec.ts)
- **E5.3 — Pass.** By the third fixture solve, the ledger held 75 base XP and three separate bonuses of 50 featured XP, 30 daily quest XP, and 100 weekly quest XP. The 255 XP total reached Level 3. Rolling back the consumer transaction left 75 base XP. Replaying the committed event preserved six ledger entries, five evidence records, and one achievement. Restarting the packaged app preserved a separate fixture’s reward projection, ledger entry IDs, and timestamps. The browser showed Level 2 at 105 demo XP after a single solve, while the member’s group score used the 25 base XP. Account erasure removed related reward evidence, achievements, and coding days. [Rewards integration tests](../../../backend/src/test/java/com/leetgrind/engagement/PracticeRewardsIntegrationTest.java) · [Groups browser test](../../../frontend/tests/groups.spec.ts) · [Erasure integration tests](../../../backend/src/test/java/com/leetgrind/integration/SubmissionSyncIntegrationTest.java)

The local Phase 5 exit criteria passed with synthetic data. Live scoring remains gated on the Phase 0 provider and ownership checks. Hosted CI verification is pending. [Phase 0 integration decision](../phase-00/evidence.md)
