# Phase 7 evaluation — Personal analytics and recommendations

Use the [phase plan](../../phase-wise-implementation-plan.md#phase-7--deliver-personal-analytics-and-recommendations) and [analytics edge cases](../../edgecase.md#analytics-and-recommendations).

## Checks

- [x] **E7.1 — Summary accuracy (AN-01, AN-02).** Use a fixture with duplicate submissions, a multi-topic problem, and a problem with missing topics. Verify unique overall and difficulty totals, the appropriate per-topic counts, and expected week and month buckets. Reconcile XP progression against the ledger and consistency against coding days.
- [x] **E7.2 — Historical coverage (LC-06, AN-01, AN-02).** Simulate incomplete history and verify the UI reports the coverage limit and labels topics as practice coverage. When opted-in import becomes available, test one new historical problem, one duplicate, and the opt-out path. Expect one additional distinct solve and the same XP and standings.
- [x] **E7.3 — Recommendations (AN-02, AN-03).** Verify that identical inputs produce stable suggestions, each with a reason. Exclude solved problems by canonical ID. Verify sparse histories, missing topics, tied candidates, and an exhausted catalog.

## Evidence and exit gate

Attach fixture totals, API output, coverage and recommendation views, and before-and-after competitive totals for any enabled historical import. Pass when the reported metrics match the fixture and recommendations behave consistently with the available data.

### Local fixture run on 21 November 2026

The candidate uses OpenAPI `0.7.0` and Flyway V7. All 35 MySQL integration tests, seven browser tests, and three offline Phase 0 evaluator tests passed. The frontend build passed.

- **E7.1 — Pass.** The fixture returned five distinct solves across four coding days, with difficulty counts of two Easy, two Medium, and one Hard. Array counted twice, Sliding Window and Hash Table each counted once, and one unmapped problem remained in the overall and difficulty totals. A repeated solve under a renamed slug kept its first credit. January returned 55 XP, February returned 45 XP, and the earlier balance of 50 XP brought the cumulative total to 150. The weekly buckets beginning 26 January and 2 February returned 90 XP and 10 XP. Both streaks reached three days. The browser showed 105 demo XP and supported drilling down to an earlier UTC month on a 375-pixel viewport. [Analytics integration tests](../../../backend/src/test/java/com/leetgrind/analytics/AnalyticsIntegrationTest.java) · [Analytics browser test](../../../frontend/tests/practice.spec.ts)
- **E7.2 — Pass for coverage.** A fixture with incomplete history returned `PARTIAL` and retained the last successful checkpoint date. The browser displayed coverage limits and topic practice labels. Live and demo activity stayed separate, and the analytics endpoint used the signed-in account. The opt-in import and opt-out checks are not applicable until supported historical imports ship. [Analytics integration tests](../../../backend/src/test/java/com/leetgrind/analytics/AnalyticsIntegrationTest.java) · [Phase 0 integration decision](../phase-00/evidence.md)
- **E7.3 — Pass.** Sparse and unmapped histories used the Easy-first fallback. A mapped-history fixture placed topics with zero recorded solves ahead of a same-difficulty problem whose topics had already been practiced. Ties resolved by slug, identical inputs gave the same recommendations, solved IDs were excluded after a slug rename, and exhausting the catalog returned an empty list. [Analytics integration tests](../../../backend/src/test/java/com/leetgrind/analytics/AnalyticsIntegrationTest.java)

An upgrade from a packaged Phase 6 instance backfilled Array and Sliding Window for a previously credited problem whose slug had changed. The learner kept 105 demo XP and 25 base group XP, and Analytics excluded that problem from suggestions. The enabled Phase 7 exit checks passed locally. Hosted CI verification is pending.
