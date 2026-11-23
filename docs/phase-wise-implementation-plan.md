# LeetGrind phase-wise implementation plan

This roadmap turns the [product requirements](implementation-plan.md) and [system architecture](architecture.md) into deliverables and release gates. LeetGrind’s first release lets a learner link a verified LeetCode profile, join a private study group, solve a problem on LeetCode, and see eligible XP reflected in the group’s weekly standings.

## Release milestones

- **Foundation and feasibility** — Phases 0–1 establish what LeetCode activity can be verified and set up the application. These phases can run in parallel.
- **Private-group MVP** — Phases 2–4 deliver and validate the complete practice-to-leaderboard workflow. The group and standings work in Phase 3 can begin against test data while Phase 2 integrates the provider. Release requires both phases and the production checks in Phase 4.
- **Post-MVP product** — Phases 5–7 add engagement, broader competition, and insights. Analytics can begin once the core solve and XP records are stable. Features that depend on topic metadata or challenge events wait for those inputs.
- **Capacity improvements** — Phase 8 uses measured sync volume and query performance to decide whether the initial deployment needs changes.

## How to evaluate each phase

Use the linked evaluation checklist under each phase and the shared [edge-case registry](edgecase.md). For each check, record the candidate version, environment, fixture or reproduction steps, expected and observed result, and a link to the evidence. Record a status of Pass, Fail, Blocked, or Not applicable. Use Not applicable only for a feature the phase explicitly treats as conditional and record why. A phase reaches its release gate when all required checks pass.

## Phase 0 — Prove the LeetCode integration

[Evaluation criteria](evaluation/phase-00/eval.md)

**Status** — The offline evidence checker and synthetic fixtures pass. Live scoring remains gated on supported provider access, verified ownership, and confirmed submission data. [Phase 0 evidence](evaluation/phase-00/evidence.md)

**Depends on** Approved test profiles and a defined set of test submissions.

- [ ] Exercise the public integration with known accepted and unsuccessful activity. Record which fields can reliably establish provider identity, submission identity, acceptance, event time, problem identity, difficulty, and available history.
- [ ] Capture sanitized fixtures for representative responses, including incomplete records, throttling, and schema changes. Determine how the adapter can distinguish an empty response from a failed or incomplete fetch.
- [ ] Validate a supported way to prove profile control. Document what happens when ownership is disputed, a verified link is disconnected, or a provider identity must change accounts.
- [ ] Record the first-release scoring decisions. Confirm whether rewards begin at verified link activation, how to handle an older submission discovered after the initial award, and which UTC periods apply. Use the observed coverage to set the initial polling and retry policies.

**Exit criteria.** A written decision identifies what the adapter can verify, the ownership method, and the limits of sync coverage. Unverified handles may be used for a private preview if supported. Keep live competitive XP disabled until ownership and submission eligibility can be verified. If either is unresolved, proceed with the application foundations and fixture-based development.

## Phase 1 — Set up the application and identity

[Evaluation criteria](evaluation/phase-01/eval.md)

**Status** — Implemented and verified locally using a clean Docker Compose start, seven MySQL integration tests, and two browser tests. The GitHub Actions workflow is configured for hosted verification.

**Depends on** The planned React, Spring Boot, and MySQL stack. Can start alongside Phase 0.

- [x] Set up the React and TypeScript frontend, Spring Boot modular monolith, and local Docker environment with MySQL. Establish module ownership, versioned migrations, and separate request and worker executors.
- [x] Publish the initial `/api/v1` OpenAPI contract, including error codes, correlation IDs, and pagination conventions. Add build, test, and migration checks to CI.
- [x] Implement registration, login, refresh, logout, and profile management. Add the account and refresh-session schema, signed short-lived access JWTs, rotated refresh credentials, and protections for cookie-backed auth routes.
- [x] Build the sign-in and profile screens. Establish the public LeetGrind identity fields that later group endpoints will expose.
- [x] Add an integration test environment that runs MySQL migrations and exercises real uniqueness and transaction behavior.

**Exit criteria.** A clean environment starts from documented commands and applies all migrations. A test user can register, sign in, refresh, sign out, and update their profile. CI checks authentication failures, refresh rotation, revocation, and protected routes.

## Phase 2 — Link profiles, ingest submissions, and credit XP

[Evaluation criteria](evaluation/phase-02/eval.md)

**Status** — Implemented and verified locally with synthetic provider activity. Live linking and competitive XP remain gated by Phase 0.

**Depends on** Phase 1. Fixture-based work can start before Phase 0 closes. Live scoring requires Phase 0 approval.

- [x] Implement pending, verified, and disconnected link states. Enforce unique provider identity binding and one active verified link per user. Keep link history, and increment the link version on disconnect or replacement to invalidate in-flight work.
- [x] Add the problem catalog, submission records, distinct solve records, append-only XP ledger, and transactional outbox. Enforce uniqueness at the provider submission, user/problem, and reward-key levels.
- [x] Implement scheduled and user-requested sync with one job per link version, bounded concurrency, database leases, and retry backoff. Fetch outside the write transaction and recheck the lease and link version before committing.
- [x] Normalize accepted activity and resolve problem identifiers and difficulty. Commit a qualifying solve, its base award of 10, 25, or 50 XP, and the corresponding outbox event together. Hold unverifiable items for resolution or mark their coverage as uncertain.
- [x] Expose personal XP history, last sync attempt, last successful sync, and safe failure reasons. Only advance the successful checkpoint when the intended range completes. Preserve prior credits when a run is partial or fails.

**Exit criteria.** A verified eligible Medium solve earns 25 XP once. Duplicate delivery, overlapping runs, repeat solves, expired leases, and disconnecting during a run leave totals consistent. A failed or partial sync retains the previous successful checkpoint and shows the correct state to the owner.

## Phase 3 — Deliver private groups and weekly standings

[Evaluation criteria](evaluation/phase-03/eval.md)

**Status** — Implemented and verified locally with MySQL and a two-account fixture run. Live scoring remains gated by Phase 0.

**Depends on** Phase 1 for group development. The combined milestone also requires Phase 2.

- [x] Implement private group creation, owner controls, expiring invitations, and member join, leave, removal, and rejoin. Keep separate membership intervals and redeem invitations in the same transaction that creates membership.
- [x] Implement weekly standings from base solve XP. Score by the submission’s effective time, strictly after joining and before leaving, within the applicable Monday-start UTC week. Apply the agreed tie and pagination behavior. Preserve scores earned during valid membership intervals.
- [x] Require current membership for private group reads and owner privileges for management actions. Use group response fields that expose LeetGrind identities and omit provider handles and sync details.
- [x] Complete the browser flow for profile linking, invite redemption, sync status, personal progress, and weekly standings. Refresh the affected views when a sync completes.

**Exit criteria.** Using two accounts, one learner creates a private group and a second joins by invite. A qualifying Medium solve after joining appears once as 25 XP in personal progress and the weekly standings. Activity before joining or between memberships earns no points for that group. An outsider or former member cannot read the private standings, and peers see only shareable LeetGrind profile fields.

## Phase 4 — Harden and release the private-group MVP

[Evaluation criteria](evaluation/phase-04/eval.md)

**Depends on** Phases 0–3.

- [ ] Deploy the React build and Spring Boot API under one HTTPS origin. Exercise schema migrations, compatible rollback, worker shutdown, backups, and a restore in a staging environment.
- [ ] Monitor API errors, provider throttling, sync age, expired leases, XP deduplications, and leaderboard latency. Document how to pause the provider integration, investigate failures, and replay jobs safely.
- [ ] Agree on account retention and deletion behavior and implement the agreed handling of active sessions, links, personal data, and historical group scores before onboarding.
- [ ] Run the two-account journey with verified test profiles. Exercise midnight and week boundaries, duplicate invitation redemption, worker crashes before and after commit, malformed provider responses, and recovery from failed syncs.
- [ ] Release to a limited group first. Review sync freshness, scoring accuracy, and privacy behavior before broadening access.

**Exit criteria.** The live workflow passes the two-account test, retries preserve scores, and removed users lose private group access. In staging, the team verifies backup recovery, the agreed account deletion behavior, and resumption of a paused sync without losing confirmed rewards.

## Phase 5 — Add recurring practice and personal rewards

[Evaluation criteria](evaluation/phase-05/eval.md)

**Depends on** The solve, catalog, XP ledger, and outbox from Phase 2. Delivers after the MVP. Topic-based quests require reliable topic mappings.

- [ ] Add idempotent outbox consumers and rebuildable coding-day summaries. Use eligible solve dates for current and longest streaks, and show an activity calendar.
- [ ] Add versioned level thresholds and achievement rules. Credit milestones once. Implement the 5 XP qualifying daily login reward as a separate personal reward.
- [ ] Publish the Problem of the Day with its title, difficulty, topics, LeetCode link, and scoring window. Track individual and group completion and credit the +50 XP bonus once.
- [ ] Add daily and weekly quests with target and completion rules. Persist completion evidence and credit +30 XP or +100 XP once, respectively.
- [ ] Set scoring cutoffs for delayed activity and add progress views. Keep personal bonuses outside MVP group scoring unless an explicit group competition rule includes them.

**Exit criteria.** Replaying a solve event never repeats a bonus or achievement. Daily and weekly boundary tests cover eligible, late, and out-of-window activity. A late solve can update a streak or open quest according to the published rules.

## Phase 6 — Expand group competition

[Evaluation criteria](evaluation/phase-06/eval.md)

**Depends on** Phase 3 for memberships and standings. Challenge notifications use committed events from the workflow introduced in Phase 5.

- [ ] Add monthly and all-time group standings, with tested period boundaries and the same membership eligibility rules. Add public groups and decide what is visible to nonmembers. Confirm participation and visibility rules before enabling any platform-wide leaderboard.
- [ ] Add group challenges and one-on-one duels with invitations, participant enrollment, published scoring rules, deadlines, and progress. Support specific problems, solve counts, and difficulty goals.
- [ ] Apply the approved ingestion grace period. Reconcile from authoritative solve records before persisting final results, using the event time and the overlap of challenge, enrollment, and any required membership windows.
- [ ] Add group activity views and in-app notifications for invitations, challenge results, achievements, and relevant standings changes. Deduplicate notifications and check current permissions for the underlying resources.

**Exit criteria.** Leaving and rejoining preserves the correct scores for all leaderboard periods. A challenge can be completed and finalized end to end, including tests for ties, duplicate events, late arrivals, and activity outside the eligibility window. Removed members cannot reopen private content through an old notification.

## Phase 7 — Deliver personal analytics and recommendations

[Evaluation criteria](evaluation/phase-07/eval.md)

**Depends on** Stable solve and XP records from Phase 2 and coding-day summaries from Phase 5 where used. Topic-level views and recommendations require catalog topic mappings.

- [ ] Build personal summaries by difficulty, topic, and period. Show XP progression and practice consistency. Distinguish recorded practice coverage from proficiency, and surface limitations in imported history.
- [ ] If historical import is supported and enabled by the user, merge verified older activity into personal solved counts by canonical problem ID. Keep historical import outside competitive rewards.
- [ ] Add deterministic problem recommendations that prioritize under-practiced topics and suitable difficulty, exclude already solved problems, and explain each suggestion.

**Exit criteria.** Summary totals match known input fixtures, including multi-topic problems and overlapping imported history. Historical import leaves XP and standings unchanged. Recommendations exclude solved IDs and handle incomplete history.

## Phase 8 — Improve capacity where measurements support it

[Evaluation criteria](evaluation/phase-08/eval.md)

**Depends on** A working MVP and representative traffic or load-test data. Can run alongside Phases 5–7.

- [ ] Measure busy leaderboard queries, worker backlog, sync freshness, and provider request volume. Compare observed results with the deployment’s agreed performance and freshness targets.
- [ ] Tune queries, indexes, job batch sizes, and worker concurrency first. Evaluate cached or materialized standings and separate worker replicas if measurements show a need.
- [ ] If Redis is introduced, verify private-group authorization before serving cached views. Test invalidation, cache loss, and fallback to MySQL.

**Exit criteria.** Record the measured bottleneck and the effect of any change. Re-run the XP deduplication, standings privacy, and worker recovery checks for the resulting deployment.
