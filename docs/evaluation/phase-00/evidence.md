# Phase 0 evidence and integration decision

**Decision on 21 November 2026** — Competitive scoring is blocked. Fixture-based development can proceed. No live submission data or ownership proof was obtained, and no programmatic LeetCode requests were made.

The prototype now allows an on-demand public GraphQL profile preview without an ownership check. The preview is separate from the submission sync, XP ledger, and group standings. This does not establish production provider access or competitive eligibility.

The [LeetCode Terms of Service](https://leetcode.com/terms/) prohibit scraping and restrict unattended processes. The published material reviewed did not establish a supported third-party endpoint or an ownership proof for this use case. A public profile and an approved test account alone do not establish permission to run an automated integration. Obtain a provider-supported access path or explicit authorization covering the proposed requests before running a live probe.

## E0.1 — Field inventory

The [coding practice guide](https://support.leetcode.com/hc/en-us/articles/360012016874-Start-your-Coding-Practice) confirms that submissions are recorded in the user’s submission history. It does not document which fields are exposed to a third party. The entries below are requirements for a future authorized adapter and indicate the current evidence.

| Required evidence | What to confirm during the controlled trial | Current evidence |
| --- | --- | --- |
| Profile identity | An account identifier stable across handle changes and attributable to the authorized activity feed | The [username help article](https://support.leetcode.com/hc/en-us/articles/360011884474-How-do-I-change-my-username) documents that handles can change. A stable integration identifier is unverified. |
| Submission identity | A stable ID and uniqueness scope that survive retries and overlapping pages | Unverified. Fixture submissions use synthetic IDs. |
| Acceptance | An authoritative accepted status, or an explicitly documented accepted-only feed | User-facing submission history is documented. Visibility and status semantics for an integration are unverified. |
| Event time | A provider timestamp with known timezone and precision for the qualifying event | Unverified. The fixture contract uses offset-aware ISO timestamps. |
| Problem identity | A canonical ID stable across title or slug changes | Unverified. Fixture problem IDs are synthetic. |
| Difficulty | Trusted Easy, Medium, or Hard metadata bound to that canonical problem | Unverified. Fixture difficulty is supplied by the modeled normalized response. |
| History and limits | Page or cursor guarantees, observed recent-feed limits, what a successful empty result means, and permitted request volume | Unverified. A capped or otherwise incomplete feed cannot prove full coverage. |

## E0.2 — Ownership and link lifecycle

Competitive eligibility requires proof of control through a provider-supported method. Validation must bind that proof to the immutable provider identity and the intended LeetGrind account. Public lookup and handle matches alone leave the link unverified. No provider password, browser session credential, or private account content is required by the proposed design.

For fixture-based work, implement the planned pending, verified, and disconnected states and exercise the following transitions using synthetic evidence.

- Hold a single verified binding per provider identity. If another user claims an existing binding, keep the original binding and return a generic conflict.
- On disconnect or replacement, invalidate in-flight work using the link version. Preserve confirmed rewards and scoring provenance under the retention policy. Require proof again before relinking.
- On a disputed claim or transfer, leave the new claim pending until the existing binding and new ownership proof are resolved. Preserve the history of the prior binding and prevent old submissions from generating new awards.
- Keep live preview and competitive scoring gated until the associated provider access is supported. The local fixture preview can operate independently.

No live ownership demonstration was performed.

## E0.3 — Reproducible fixture evidence

The [fixture set](../../../scripts/phase0/fixtures.json) is fully synthetic. It models the **normalized evidence contract** expected by the checker, independent of any wire schema. Run from the workspace root with Python 3.11 or newer.

```sh
python3 -B scripts/phase0/evaluate.py
python3 -B -m unittest discover -s scripts/phase0 -v
```

The [checker](../../../scripts/phase0/evaluate.py) accepts a local JSON file of named cases. Each case provides an `expected_profile_id` and an `evidence` object. The evidence gives a transport outcome, and for HTTP cases it includes a status and, when applicable, a normalized body. A usable body identifies the profile, gives the attempted range state and its submission list, and can include a list of upstream errors. Mark a range `complete` only when the authorized source establishes coverage for that range. Treat unknown coverage separately from a failed request.

**Observed locally.** The [regression suite](../../../scripts/phase0/test_evaluate.py) passed all three test methods, covering 16 named fixtures and two supplemental validations. A complete empty response permitted advancing the attempted range. Partial data, unknown coverage, access errors, throttling, timeout, connection errors, schema drift, and conflicting or incomplete records did not. The overlap fixture kept three distinct accepted candidates and counted one replay despite timestamp ties and out-of-order records. An accepted candidate is a structurally valid observation. Eligibility for XP still requires verified ownership, activation and competition rules, and the application’s durable deduplication.

No feed limit, status code, error shape, or provider field name in a fixture constitutes a live observation.

## E0.4 — Initial scoring and scheduling decisions

Use these defaults for local development, consistent with the [architecture](../../architecture.md#5-rules-for-xp-time-and-visibility).

- Competitive rewards begin at verified link activation. Hold pre-activation activity to optional, consented personal history if the authorized source supports it. Until then, label any missing coverage clearly and leave historical import disabled.
- Award base XP once per user and canonical problem, for the first verified eligible accepted solve observed. Within a batch, use event time and a stable ID tie-break. An older solve discovered later does not automatically move an existing award.
- Use UTC and half-open day, week, and month windows, with weeks starting Monday. A group receives base XP only for submission times strictly inside an eligible membership interval.
- Keep live polling disabled. When provider access is confirmed, choose the polling interval, request budget, overlap, and any retry cap from documented limits and observed coverage. For fixtures, use bounded retries with backoff and jitter for transient failures, honor a provider retry delay if supplied, and pause on access errors or incompatible schemas. A partial or failed fetch retains the last successful checkpoint. Unknown coverage remains visible to the account owner.

## Controlled trial after access is available

1. Record the provider-supported access method, permitted requests, and the approved test profile references in the experiment record. Use synthetic aliases in committed materials.
2. On a controlled profile, record a known accepted submission and a known unsuccessful one using the normal product workflow. Use the authorized integration to compare what is visible, whether IDs and timestamps match, and how accepted-only or capped results are documented. Check repeat reads or permitted page overlaps for stable identities.
3. Demonstrate the ownership method on the controlled account, then test a duplicate claim and disconnect. Use local fixtures for throttling and failures instead of provoking them on the provider.
4. Document the confirmed coverage and request limits, sanitize any approved evidence, and re-evaluate E0.1–E0.4. Enable live scoring only once authorized access, ownership, and the required scoring inputs are confirmed.
