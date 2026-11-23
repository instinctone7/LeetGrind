# Phase 1 evaluation — Application foundation and identity

Use the [phase plan](../../phase-wise-implementation-plan.md#phase-1--set-up-the-application-and-identity) and [session edge cases](../../edgecase.md#accounts-and-session-lifecycle).

## Checks

- [ ] **E1.1 — Clean start.** Start the frontend, API, and MySQL from the documented commands in a fresh environment. Apply migrations, reload the app, and confirm readiness. Verify that CI runs the build, tests, and migrations against MySQL.
- [x] **E1.2 — Account journey (ID-01).** Register, sign in, update a profile, refresh, and sign out through the browser. Race two registrations against the same normalized email and test a duplicate public handle. Each unique value has one owner and rejected requests create no extra account or session.
- [x] **E1.3 — Session lifecycle (ID-02, ID-03).** Verify credential rotation, rejection of reused and revoked refresh credentials, and family revocation on detected reuse. Reject invalid or expired access JWTs, reject a disabled account with a still-valid JWT, and enforce origin and CSRF rules on cookie-backed routes.
- [x] **E1.4 — API contract.** Compare successful and failed identity responses against OpenAPI. Verify stable application error codes and a correlation ID without credentials in responses or logs.

## Evidence and exit gate

Attach the clean-start commands and output, CI results, and representative browser and API results. Pass when a clean environment works and all authentication and persistence checks pass.

### Local run on 21 November 2026

- **E1.1 — Local pass.** A fresh Docker Compose project started the application and MySQL on disposable ports. Flyway applied the identity migration. The readiness probe returned `{"status":"UP"}`. The TypeScript and Vite production build passed. The [GitHub Actions workflow](../../../.github/workflows/ci.yml) is configured and the hosted run remains pending.
- **E1.2 — Pass.** Both [browser tests](../../../frontend/tests/identity.spec.ts) passed against the packaged app. They covered the full account journey and a stale edit after switching accounts. The [MySQL integration suite](../../../backend/src/test/java/com/leetgrind/identity/IdentityIntegrationTest.java) returned one `201` and one `409` for concurrent registrations with the same normalized email, with one persisted account and no session.
- **E1.3 — Pass.** The integration suite passed seven tests in total. It verified refresh rotation, simultaneous replay and revocation, logout, invalid, expired, wrong-audience and wrong-issuer JWTs, disabled accounts, and cookie-route origin and header checks.
- **E1.4 — Pass.** Registration, login, and a profile validation error matched the declared status and required response fields in [OpenAPI](../../../backend/src/main/resources/static/openapi.yaml). Error bodies matched the `X-Correlation-ID` header. The final test log contained no synthetic password or access-token values.

Reproduce the checks using the [local setup and test commands](../../../README.md#develop-and-test).
