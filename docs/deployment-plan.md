# LeetGrind deployment plan

Use this runbook to deploy a hosted demo, verify it, and promote the tested release. The initial target is one Railway app service and one private MySQL service, with GitHub as the source of releases.

## Hosting layout

- **Frontend and API on Railway.** The root [Dockerfile](../Dockerfile) builds React with Node.js 24 and packages the output into the Java 21 Spring Boot application. One HTTPS origin serves the UI and `/api/v1`, so the frontend’s relative requests and the `Secure`, `SameSite=Strict` refresh cookie work together. Streamlit Community Cloud uses a Python entrypoint, so it would require a frontend rewrite. [Railway Dockerfile builds](https://docs.railway.com/builds/dockerfiles) · [Streamlit deployment](https://docs.streamlit.io/deploy/streamlit-community-cloud/deploy-your-app/deploy)
- **Database on Railway.** Use a single MySQL 8.4 service with persistent storage at `/var/lib/mysql`. Run the app and database in the same region and environment, use private networking, and expose HTTPS only on the app service. MySQL holds accounts, sessions, groups, XP, and job state. Confirm the template image version matches 8.4 before using it, or provision `mysql:8.4` with its persistent volume and matching variables. [Railway MySQL](https://docs.railway.com/databases/mysql) · [Private networking](https://docs.railway.com/networking/private-networking)
- **GitHub for source and CI.** Use the existing [Application checks workflow](../.github/workflows/ci.yml) to run backend integration tests, the frontend build, and browser tests. Protect the release branch with the check and keep credentials and database dumps out of the repository.
- **Redis and RedisInsight.** Start with MySQL and one app replica. The current code uses MySQL for durable state and in-process throttling, so a Redis service would sit idle. Consider it if multiple replicas need shared throttling and the app is updated to use it. RedisInsight can run locally if Redis is introduced.

## First deployment

1. **Prepare GitHub.** Use the [LeetGrind repository](https://github.com/instinctone7/LeetGrind) as the Railway source. Keep local environment files and database exports outside Git. Use `main` as the production branch and create a `staging` branch for deployment rehearsals.
2. **Create Railway staging.** Create a project with separate `staging` and `production` environments. Give each its own MySQL volume, database credentials, JWT key, and public app origin. Use synthetic accounts in staging. Choose a region near the intended users and put both services there.
3. **Provision MySQL.** Name the service `MySQL` to match the variable references below. Wait for the database to start and confirm the persistent volume is mounted. Create a dedicated app account for the selected database, with schema-scoped read, write, and Flyway migration privileges. Store its credentials as Railway variables `APP_DB_USER` and `APP_DB_PASSWORD` on the database service. Keep administrator credentials for database operations. Use the Railway SSH tunnel for administrative connections and leave the public MySQL TCP proxy disabled. [Railway database CLI connection](https://docs.railway.com/cli/connect)
4. **Create the app service.** Name it `leetgrind-app`, connect the staging branch, and use the repository root as the source directory. Railway detects `Dockerfile` and runs its existing entrypoint. Use one always-on replica with no persistent app volume. Set the public domain’s target port to `8080` and set the health check path to `/actuator/health/readiness`. The probe includes a database check. [Railway domains and target ports](https://docs.railway.com/networking/domains/working-with-domains) · [Railway deployment health checks](https://docs.railway.com/deployments/healthchecks)
5. **Set variables and deploy.** Generate a Railway HTTPS domain or add your custom domain. Use that exact HTTPS origin in the app variable below, with no trailing slash or path. A domain change also requires an origin change. Apply the variables and deploy. Check the build log for the Dockerfile build and the runtime log for successful Flyway migration and Hibernate validation. Flyway runs on application startup against the configured schema.
6. **Promote after staging passes.** Configure the production services the same way with production values and promote the tested commit to `main`. Require the GitHub check before merge and enable Railway’s **Wait for CI** for autodeploys if available. For a first release or a release with schema changes, use a controlled release window with a database checkpoint and the staging-tested commit recorded. [Railway GitHub autodeploy controls](https://docs.railway.com/deployments/github-autodeploys)

### MySQL account setup

With the Railway project linked locally, open an administrative database session using `railway connect MySQL --environment staging --ssh`. Run the SQL below once per environment, replacing `railway` with the schema named in that service’s `MYSQLDATABASE`. Copy the generated password directly into `APP_DB_PASSWORD` and set `APP_DB_USER` to `leetgrind_app`.

```sql
CREATE USER 'leetgrind_app'@'%' IDENTIFIED BY RANDOM PASSWORD;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON `railway`.* TO 'leetgrind_app'@'%';
```

### Railway app variables

Paste the [Railway variable template](../deploy/railway/app.env.example) into the app service’s Variables tab. Replace the JWT placeholder with the output of `openssl rand -base64 32` and enter the app’s HTTPS origin. Replace `MySQL` in the references if the database service has a different name. These references resolve in Railway. `MYSQLDATABASE` must match the schema on which the dedicated app account has privileges. Spring Boot listens on `PORT` and defaults to `8080`. [Railway reference variables](https://docs.railway.com/variables) · [Hosted Spring Boot profile](../backend/src/main/resources/application-hosted.yml)

The `hosted` profile uses HTTPS cookies, disables fixture mode and the fixture sync scheduler, and defaults registration to `CLOSED`. Existing users can sign in. For a controlled rehearsal, set `LEETGRIND_SIGNUP_MODE=PILOT` and `LEETGRIND_PILOT_EMAILS` to a comma-separated list of approved synthetic email addresses. Restrict access separately for a real pilot because the allowlist does not prove email ownership. Keep the production JWT key stable across routine releases and different from staging’s key. [Existing signup and recovery policy](phase4-release-runbook.md#account-retention-and-pilot-policy)

### LeetCode launch scope

- The `hosted` profile disables LeetCode previews and public solve sync by default. To enable them, set `LEETGRIND_PROFILE_PREVIEW_ENABLED=true` and point `LEETGRIND_PROFILE_SERVICE_URL` at an approved service implementing `GET /api/leetcode/user/{handle}`. The response must supply the saved username and the newest accepted submission’s `titleSlug`, Unix timestamp in seconds, and `difficulty` as `EASY`, `MEDIUM`, or `HARD`. The app can resolve missing difficulty for problems in its own catalog. Hosted startup requires the service URL when previews are enabled. [Profile preview implementation](../backend/src/main/java/com/leetgrind/integration/ProfilePreview.java)
- Users verify their saved handle with a code in their public LeetCode Real Name. Sync checks for that code each time and credits the newest eligible accepted solve, including one already visible at verification. Group rankings use the solve’s original timestamp and the group membership interval. The public feed has limited coverage. Keep the fixture scheduler disabled in production. [Provider integration constraints](evaluation/phase-00/evidence.md)
- Timed duels validate problems against the local catalog or recorded problem metadata. To support other LeetCode problems in hosted mode, the approved profile service can expose `GET /api/leetcode/problem/{slug}` returning `titleSlug`, `title`, and `difficulty`.

## Release checks

GitHub Actions runs the browser suite against the packaged Docker image and rehearses the hosted profile over HTTPS with a disposable MySQL database. The HTTPS rehearsal checks registration policy, session cookies and refresh, preview gating, and check-in XP. [Application checks workflow](../.github/workflows/ci.yml)

```sh
APP_ORIGIN=https://your-app.up.railway.app
curl -fsS "$APP_ORIGIN/actuator/health/readiness"
curl -fsS -o /dev/null "$APP_ORIGIN/"
curl -sS -o /dev/null -w '%{http_code}\n' "$APP_ORIGIN/api/v1/leaderboard"
```

Expect readiness `UP`, a successful app response, and `401` for the unauthenticated leaderboard request. In restricted staging, use two test users to check signup, sign-in, session refresh after a reload, and sign-out. Check that a private group invitation works and that an outsider cannot view the group. Verify a check-in awards 5 XP once per UTC day and updates **Individual** standings. With a group joined on an earlier UTC day, verify the next eligible check-in also updates **Group** standings. Confirm group selection, previous periods, and automatic refresh while the leaderboard is visible.

Test sign-in from two networks behind Railway’s edge and review any unexpected `429` responses. The app throttles using the servlet’s remote address. Confirm the address reflects users before opening access widely. If users share a proxy address, configure client IP handling for the trusted proxy path and retest. [Auth request filter](../backend/src/main/java/com/leetgrind/identity/AuthRequestFilter.java)

## Data, backups, and rollback

- **Choose a starting dataset.** For a clean launch, let Flyway create the database. To move existing local data, export a transactional dump including `flyway_schema_history`, import it into an empty isolated MySQL 8.4 database before starting the app, and compare row counts for accounts, groups, solves, and XP. Rehearse with synthetic data. For an approved production transfer, pause writes during the final export, connect through the Railway SSH tunnel, and handle the dump as sensitive data. [Existing dump and restore procedure](phase4-release-runbook.md#back-up-restore-and-roll-back) · [Railway CLI tunnel](https://docs.railway.com/cli/connect)
- **Schedule backups and rehearse a restore.** Enable daily volume backups, take a checkpoint before schema releases, and test restoring an encrypted logical dump into a separate environment. Railway volume backups restore within their original project and environment. Daily snapshots are kept for six days. As an initial target, plan for up to 24 hours of data loss between daily backups and a recovery within two hours, then measure recovery in rehearsal. Agree on retention and reconcile account erasures before reopening a database restored from backup. [Railway backup schedules and restore limits](https://docs.railway.com/volumes/backups)
- **Roll back app code when the schema is compatible.** Record the release commit, previous successful Railway deployment, Flyway version, and backup time before launch. If the app fails post-deploy checks, use the previous successful Railway deployment and verify its restored configuration, especially the database reference and origin. App rollback restores the image and variables while the database keeps its current schema. For an incompatible migration, use a verified forward fix or restore the approved recovery point with writes paused, then verify data and access before resuming traffic. [Railway deployment rollback](https://docs.railway.com/deployments/deployment-actions)

## Operations and budget

- Add an external uptime check for `/actuator/health/readiness` because Railway’s deployment check stops after activation. Review restarts, persistent errors, latency, memory, and database volume use. Keep `/actuator/metrics` restricted to its existing local authorization path. Investigate errors with the correlation ID in the logs. [Railway health check behavior](https://docs.railway.com/deployments/healthchecks) · [Railway observability](https://docs.railway.com/observability)
- Railway Hobby is a reasonable starting tier for a small demo. The published minimum is **US$5 per month**, credited against usage. The bill depends on app and database compute, storage, backup usage, and egress, and a second always-on environment adds usage. Set a billing alert, measure a week of real usage, and budget from that measurement. A hard limit can stop workloads. [Railway pricing](https://docs.railway.com/pricing) · [Railway cost controls](https://docs.railway.com/pricing/cost-control)

## Optional Vercel split

For separate frontend hosting, use the [hosting settings guide](hosting-settings.md) for Vercel, Railway, Railway MySQL, and optional Redis Insight.
