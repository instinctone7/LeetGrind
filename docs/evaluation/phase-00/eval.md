# Phase 0 evaluation — LeetCode integration feasibility

Use the [phase plan](../../phase-wise-implementation-plan.md#phase-0--prove-the-leetcode-integration) and [provider edge cases](../../edgecase.md#provider-identity-and-data-coverage).

**Current decision** — Live scoring is blocked. The [Phase 0 evidence](evidence.md) records the field inventory, local test results, and conditions for a controlled live trial.

## Checks

- [ ] **E0.1 — Submission evidence (LC-03, LC-05).** Compare known accepted and unsuccessful activity from approved test profiles with the public responses. Document how the integration establishes profile identity, submission identity, acceptance, event time, canonical problem, and difficulty. If a required field cannot be established, mark the corresponding scoring path blocked.
- [ ] **E0.2 — Ownership (LC-01, LC-02).** Demonstrate the supported verification method with a controlled profile. Show that a resolvable public handle alone cannot verify ownership. Document how duplicate claims, disconnects, and disputed transfers will be handled. If there is no supported method, keep live competitive scoring blocked.
- [ ] **E0.3 — Coverage and failures (LC-04, LC-05).** Record observed feed limits and collect sanitized fixtures for success and empty responses. Use controlled fixtures for partial data, throttling, timeouts, and shape changes. Label observed behavior and simulated failures in the evidence.
- [ ] **E0.4 — Rules (LC-06).** Record the approved activation, historical import, late-discovery, UTC period, polling, and retry decisions. Identify any unverified history ranges visible to the user.

## Evidence and exit gate

Attach the field inventory, sanitized fixtures, ownership demonstration, known coverage limits, and a live-scoring go/no-go decision. Pass for live scoring only when ownership and all required scoring inputs are verified. Otherwise, mark live scoring blocked and record which fixture-based work can proceed.

- **E0.1 — Blocked for live scoring.** Field requirements and local validation are recorded. No approved live responses were available for comparison.
- **E0.2 — Blocked.** The link lifecycle decisions are documented. A supported ownership demonstration remains outstanding.
- **E0.3 — Pass for fixture coverage, blocked for live coverage.** All synthetic scenarios passed. Provider limits and coverage remain unverified.
- **E0.4 — Local defaults recorded.** Live polling and retry settings remain blocked on the authorized trial.
