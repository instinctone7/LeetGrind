# LeetGrind edge cases

The [product plan](implementation-plan.md) defines the user-facing behavior and the [architecture](architecture.md) defines the proposed implementation. Use this registry with the [phase-wise plan](phase-wise-implementation-plan.md) to design tests. Cases describe expected behavior. Implementation and test status belong in each phase’s evaluation evidence.

## Shared fixtures

- Use two verified learners and a third account that is outside the group. Use synthetic LeetGrind and LeetCode handles in fixtures, and approved test profiles for live integration evidence.
- Freeze the clock in UTC. For a concrete weekly test, use Monday, 5 January 2026, with the period ending at 00:00 UTC on Monday, 12 January. In the membership fixture, the learner joins at 10:00, leaves at 11:00, and rejoins at 12:00 on 5 January. Set link activation before the first submission unless the test explicitly covers activation.
- By default, assume one base award per canonical problem per user and use distinct problems for independent events. Reuse a submission ID or problem ID only where the case deliberately tests duplication. Keep submission time separate from ingestion time.
- Make concurrency deterministic with a barrier or controlled worker interruption. Assert stored state as well as API responses. Use MySQL for cases that depend on uniqueness, transactions, or job locks.

## Provider identity and data coverage

| ID | Scenario | Expected behavior |
| --- | --- | --- |
| LC-01 | The handle resolves, but the learner has not yet proven control. Two users try to verify the same provider identity. | Public lookup alone cannot grant rewards. Verification requires the supported ownership method. At most one LeetGrind account holds the verified binding. A conflict reveals no other account details. |
| LC-02 | A handle changes, a learner disconnects and relinks, or ownership is disputed. | Preserve provenance and previously earned credit, subject to retention policy. Follow the approved verification or transfer procedure. Relinking does not reissue rewards. |
| LC-03 | A record is rejected, has an ambiguous acceptance state, or lacks trustworthy IDs, difficulty, or event time. | Write no solve or XP until eligibility is established. Treat accepted but incomplete activity according to the retry and coverage policy. |
| LC-04 | The provider returns an empty successful result, a partial response, a capped feed, a timeout, a throttle response, or an incompatible payload. | Distinguish these outcomes. A complete empty fetch may update last success for the attempted range. Partial and failed fetches preserve the previous successful checkpoint. A capped feed does not prove full historical coverage. |
| LC-05 | The same submission appears in overlapping pages, two submissions share a timestamp, or a page arrives out of order. | Retain all distinct eligible events the source exposes. Use a stable provider cursor or overlapping retrieval with ID deduplication. |
| LC-06 | A valid solve predates verified activation, or an older solve for the same problem appears after an award. | Apply the agreed activation and late-discovery rules. Under the architecture’s proposed defaults, pre-activation activity can contribute to opted-in personal history only, and late discovery does not move the existing award. |

## Accounts and session lifecycle

| ID | Scenario | Expected behavior |
| --- | --- | --- |
| ID-01 | Registrations race with the same normalized email or unique public handle. | One account owns each unique value. Losing requests receive a controlled response without creating extra accounts or sessions. |
| ID-02 | A refresh credential is rotated, reused, or presented after sign-out. | Only the current credential in an active family can refresh. Apply session-family revocation on detected reuse or logout. Expired or invalid access JWTs cannot authenticate. |
| ID-03 | A disabled user has a valid access JWT, or a cookie-backed auth request violates the origin or CSRF policy. | Deny the protected request. Return the documented error without credentials or internal details. |

## Sync, transactions, and XP

| ID | Scenario | Expected behavior |
| --- | --- | --- |
| SY-01 | A manual refresh overlaps a scheduled sync or two workers claim expired work. | Keep one schedulable job per link version. Only the current lease holder can commit its results. A reclaimed job is safe to retry. |
| SY-02 | A profile is disconnected or replaced while an upstream request is in flight. | The stale link version cannot write XP or advance the replacement link’s checkpoint. Prior committed credit remains. |
| SY-03 | A worker fails before commit, after commit, or after completing only part of an intended fetch. | The solve, base XP, and outbox event commit together. Replaying committed work adds no extra rows. Valid partial work can remain credited while the success checkpoint stays at its prior value. |
| SY-04 | The same submission is replayed, or a later accepted submission resolves to the same canonical problem under a changed slug. | Keep one base award for the problem. Distinct provider submissions can remain independently traceable. |
| SY-05 | An Easy, Medium, or Hard solve qualifies, or an existing credit needs an approved correction. | Award 10, 25, or 50 XP respectively. If a correction is applied, retain the original entry and append a single compensating entry with the original effective time. |
| SY-06 | The provider repeatedly fails or a sync is paused and resumed. | Back off and respect provider retry guidance where available. Keep credited activity and indicate stale or failed sync to the owner. Resuming does not repeat rewards. |

## Membership, privacy, and standings

| ID | Scenario | Expected behavior |
| --- | --- | --- |
| GR-01 | Two users redeem a final-use invite concurrently, or an existing member retries redemption. | At most one new membership consumes the remaining use. Retrying for an existing member consumes no further use. Expired or revoked invitations cannot create memberships. |
| GR-02 | A solve occurs before joining, exactly at join or leave, during a membership gap, or after rejoining. | Group scoring uses the effective time and requires it to be strictly after joining and before leaving. Credit only the eligible intervals. Earlier membership credit remains after departure. |
| GR-03 | A pre-join submission arrives after joining, or a Sunday submission is synced after the Monday UTC boundary. | Attribute by submission event time. Pre-join activity cannot earn group credit. A valid Sunday solve contributes to its original week. |
| GR-04 | Equal XP scores span a page boundary, or scores change while a viewer pages. | Compute shared dense ranks across the period before paging. Use stable display order and a period-bound cursor. Refresh from the first page to view updated standings. |
| GR-05 | An outsider, removed member, or ordinary member calls a private read or owner-only action. | Enforce current membership or ownership as applicable. Old links, notifications, and cached content do not grant access. Group payloads contain only allowed LeetGrind identity fields. |
| GR-06 | A group owner attempts to leave, or a learner earns an account-level bonus. | Require ownership transfer or group closure before owner departure. Base XP drives the initial group standings. Personal bonuses count only where an explicit group rule allows them. |

## Engagement and competition

| ID | Scenario | Expected behavior |
| --- | --- | --- |
| EN-01 | Multiple solves or logins occur on the same UTC day, or delayed eligible activity fills a gap. | Count one coding day per learner and date. Recompute streaks when a gap fills. Award the qualifying daily login bonus only once, independently of coding streaks. |
| EN-02 | A featured problem or quest solve is at the start or end of its window, or sync happens after the event window. | Apply the published half-open event window and the approved ingestion cutoff separately. An eligible late solve can count before the cutoff. A closed instance follows its published correction policy. |
| EN-03 | One event completes a quest and the featured problem, or a consumer retries after committing. | Apply each qualifying reward under its own key. Repeat deliveries do not add XP, completion evidence, or achievement unlocks. |
| EN-04 | A duel or challenge receives activity before enrollment, during a membership gap, or after its deadline. | Require the activity to fall in the relevant challenge, enrollment, and group membership windows. Count evidence once per participant and rule. |
| EN-05 | Two participants tie, or eligible evidence reaches the system near finalization while projections lag. | Apply the published tie and grace-window rules. Reconcile against persisted solves and store one final result. Later changes use the approved correction process. |
| EN-06 | A notification event is replayed, or a user opens a notification after losing group access. | Store one notification per trigger and recipient. Enforce current authorization at the referenced resource. |

## Analytics and recommendations

| ID | Scenario | Expected behavior |
| --- | --- | --- |
| AN-01 | One problem has multiple topics, or a verified historical import overlaps a tracked solve. | Count the canonical problem once in overall totals and once for every relevant topic. A permitted historical import changes eligible personal summaries without changing XP or standings. |
| AN-02 | History is missing, partial, or unavailable, or a problem has no topic mapping. | Expose coverage limitations, avoid claims about proficiency, and preserve consistent overall totals. Use a defined fallback when there is insufficient evidence for personalized recommendations. |
| AN-03 | Ranking inputs tie or every eligible candidate is already solved. | Given the same inputs, return stable recommendations with reasons and exclude solved canonical problems. If no candidate remains, show an empty result. |

## Release, recovery, and scaling

| ID | Scenario | Expected behavior |
| --- | --- | --- |
| OP-01 | A user is deleted while a sync is queued or in flight. | Revoke sessions and links, stop future syncs, remove personal data from visible views, and apply the chosen historical score policy across derived data. |
| OP-02 | A release is rolled back, workers stop, or the database is restored. | Verify compatible application/schema behavior. Resume or reclaim work safely and reconcile restored jobs, solves, and XP against the recovery point. |
| OP-03 | A hot leaderboard period, sync backlog, or cache outage puts pressure on MySQL. | Measure latency, backlog, and freshness against agreed targets. If a cache is deployed, fall back to bounded MySQL reads while preserving authorization and reward integrity. |
| OP-04 | Scores or membership change while a cached leaderboard is warm. | Apply the documented expiry and invalidation behavior. Authorize each private request before returning cached results. Revoked access takes effect immediately. |
| OP-05 | Provider failures, schema drift, or unusually old sync jobs trigger diagnostics. | Provide actionable status, metrics, or alerts with correlation identifiers. Keep credentials, provider payloads, emails, and provider handles out of diagnostic output. |
