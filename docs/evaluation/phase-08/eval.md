# Phase 8 evaluation — Capacity and resilience

Use the [phase plan](../../phase-wise-implementation-plan.md#phase-8--improve-capacity-where-measurements-support-it) and [operations edge cases](../../edgecase.md#release-recovery-and-scaling).

## Checks

- [x] **E8.1 — Representative baseline (OP-03, OP-05).** Record the deployment shape, dataset size, traffic mix, test duration, and latency and freshness targets. Capture leaderboard latency, sync backlog and age, provider request volume, and database load under that workload.
- [x] **E8.2 — Compare changes (OP-03).** Identify the measured bottleneck. Compare before and after with equivalent data, load, and measurement windows. Explain the outcome against the targets.
- **E8.3 — Cache or replica changes (OP-03, OP-04, SY-01, SY-03, GR-05).** Not applicable to this candidate. It uses one app and one MySQL instance and reads standings directly from MySQL. Apply the cache invalidation, private access, fallback, and overlapping-worker checks if those deployment changes ship later.
- [x] **E8.4 — Correctness regression (SY-03, SY-04, GR-05).** Re-run duplicate XP, crash recovery, and private standings tests against the resulting deployment. Compare the standings with authoritative ledger totals.

## Evidence and exit gate

Attach the workload and measurement report, configuration or query changes if used, and regression results. Pass when measurements justify the deployment decision, enabled changes meet the agreed targets, and correctness checks pass.

### Local workload and acceptance targets

The reproducible workload uses a disposable fixture-only Compose project on loopback. It caps the app at 2 vCPUs and 1 GiB, and MySQL 8.4 at 2 vCPUs and 2 GiB. The seeded private group contains 600 learners with previous practice, including leave and rejoin intervals. The database contains 108,000 historical solves and 21,600 bonus entries. A burst releases 150 fixture jobs at the start and 50 of those learners belong to the measured group. The test warms the endpoints with 60 reads, then offers 24 reads per second for 100 seconds with a mix of 70% weekly, 20% monthly, and 10% all-time standings.

Local acceptance targets are a p95 HTTP latency of 250 ms or less for each standings period, zero HTTP or client-queue errors, p95 fixture freshness within 45 seconds, and all 150 fixture jobs completed within 60 seconds. Freshness measures time from the fixture’s eligible event to the recorded first successful sync. [Benchmark runner](../../../scripts/phase8/benchmark.py) · [Synthetic seed](../../../scripts/phase8/seed.sql)

Set `baseline_image` to a built local image from the previous version and `candidate_image` to a built local image with the Phase 8 changes. Run the commands sequentially from the repository root so the workloads have the same I/O and CPU budgets.

```sh
python3 -B scripts/phase8/benchmark.py --image "$baseline_image" --output /tmp/leetgrind-phase8-baseline.json
python3 -B scripts/phase8/benchmark.py --image "$candidate_image" --output /tmp/leetgrind-phase8-candidate.json
```

The runner creates a fresh project for each run and removes its generated containers and volume afterward. The JSON records HTTP percentiles, queue samples, freshness, provider-adapter attempts where available, MySQL ranking timings and rows examined, and the authoritative XP comparison. Production acceptance needs an approved workload and live provider cadence. [Phase 0 integration decision](../phase-00/evidence.md)

### Baseline and tuning decision

The Phase 7 baseline processed 2,400 reads with zero errors. Weekly, monthly, and all-time p95 latency was 27.6 ms, 31.2 ms, and 108.7 ms, respectively. The leaderboard query and schema met the local latency target. MySQL served roughly 358 million InnoDB row reads and two physical buffer-pool reads during the measured window.

Sync was slower than both local targets. The 150 jobs drained in 89.6 seconds with 85.6-second p95 freshness and an oldest sampled due age of 89.3 seconds. The worker dispatched at most two jobs per tick while eligible work waited. The candidate processes up to four successive claims in each of its two existing slots. Each job still checks the pause control and has its own lease and transaction. The added timers record adapter calls and queue wait.

### Candidate results on 21 November 2026

- **Sync.** The same 150 jobs drained in 25.4 seconds, down about 72%. The p95 freshness fell to 23.4 seconds and the oldest sampled due age to 24.9 seconds. Both runs recorded 150 completed outcomes. The candidate also recorded 150 adapter calls directly. The fixture adapter generated zero external provider requests.
- **Standings.** All 2,400 reads succeeded. Weekly, monthly, and all-time p95 latency was 27.0 ms, 31.0 ms, and 113.8 ms. All-time p99 rose from 130.0 ms to 174.7 ms in this run. The three periods stayed under the 250 ms p95 target.
- **MySQL.** InnoDB row reads were 357.8 million on baseline and 358.2 million on the candidate. Physical buffer-pool reads were 2 and 12. The database reported a mean of 26.0 ms versus 26.2 ms across 2,400 ranking queries per run. These results support keeping the current standings query, indexes, and single-instance deployment for this workload.
- **Authoritative totals.** The candidate’s first two all-time standings pages matched 100 independently computed MySQL scores and dense ranks. The burst produced exactly 150 base awards of 25 XP, totaling 3,750 XP.

All 36 MySQL integration tests, seven browser tests, and three offline Phase 0 evaluator tests passed. The dispatch test verified that one tick finishes at most eight jobs and the next tick processes the remaining job. The regression suite checked stale leases and rollback, solve replay and exact XP credit, and immediate denial of private standings after membership removal. [Sync tests](../../../backend/src/test/java/com/leetgrind/integration/SubmissionSyncIntegrationTest.java) · [Private group tests](../../../backend/src/test/java/com/leetgrind/groups/PrivateGroupsIntegrationTest.java) · [Group browser tests](../../../frontend/tests/groups.spec.ts)

On the packaged candidate, an abandoned fixture lease survived an app restart while sync was paused. Resuming reclaimed the lease. Replaying the same fixture still left one 25 XP award, with one expired lease recovery and two adapter attempts. The operator reported the overdue job and the browser origin was denied access to the metrics. The local exit criteria passed. [Operator commands](../../phase4-release-runbook.md#diagnose-and-recover-sync)
