# LeetGrind

LeetGrind helps study groups build a consistent coding practice. The app supports profile linking, personal rewards and analytics, problem recommendations, and public and private groups with standings, challenges, and duels. See the [phase-wise implementation plan](docs/phase-wise-implementation-plan.md) for milestones and [system architecture](docs/architecture.md) for module boundaries.

## Run locally

Use Docker with the Compose plugin. From the project root, create the local environment file once.

```sh
umask 077
cp .env.example .env
printf '\nLEETGRIND_JWT_SECRET=%s\n' "$(openssl rand -base64 32)" >> .env
docker compose up --build -d --wait
```

Open **http://127.0.0.1:8080/**. Use the exact origin so browser session requests pass the origin check. The readiness probe is at `/actuator/health/readiness` and the OpenAPI contract is at `/openapi.yaml`. The application uses Flyway to apply migrations at startup. Stop the services with `docker compose down`.

### Preview a LeetCode profile

During signup, enter your display name and a LeetCode username or profile URL.

You can update your LeetCode username or profile URL in **Profile**. When sync is enabled, LeetGrind checks recent accepted problems when you open or return to the workspace and when you select **Sync now**. Each LeetCode username can be linked to one LeetGrind account. A first credited solve earns 10 XP for Easy, 25 XP for Medium, or 50 XP for Hard. Repeated refreshes and repeated accepts of the same problem cannot award solve XP again.

The read-only preview shows public solve counts, activity, and recent accepted submissions. The unofficial GraphQL response can change and covers only recent public activity. Hosted deployments use a compatible approved profile service implementing `GET /api/leetcode/user/{handle}`. [Hosted profile service setup](docs/deployment-plan.md#leetcode-launch-scope)

### Try a private group with two accounts

1. Sign in as the owner, open **Groups**, create a private group, and create an invitation. The invitation code is shown when it is created.
2. In a separate browser session, create a new learner account and join the group with that code.
3. Open the group’s standings to see both members and their XP. The owner can manage invitations and membership from the group page.

Weekly standings use Monday-to-Monday UTC windows and credit the submission time inside a membership interval. Previous scores remain visible to current members when a learner leaves. [Phase 3 evaluation](docs/evaluation/phase-03/eval.md)

### Try group competition

In **Groups**, create a private group or choose **Public** to appear in the signed-in group directory. The directory shows only a group’s name and description. Joining opens the standings, roster, challenges, and activity. If a learner leaves or is removed, their eligible historical scores remain available to current members. Public group members can join again through the directory.

- Switch among weekly, monthly, and all-time standings. Weeks start Monday at `00:00 UTC` and months start on the first at `00:00 UTC`. Group standings include verified solve XP, quest rewards, and daily check-in XP earned during eligible membership intervals.
- Open **Duels** from the workspace or your group.
- In **Instant 1v1 Challenge**, choose a group member, paste a LeetCode problem URL, set a **10–120 minute** timer, and choose a **5, 25, 50, or 100 XP** stake. The server validates the problem and sends its canonical link. A duel starts when the opponent accepts. Use **Check my solve** to sync the assigned accepted submission before the deadline. A repeat acceptance of a previously credited problem can count for the duel without earning base solve XP again.
- At the deadline, the earliest matching acceptance wins the stake. The loser forfeits the same amount. If both players time out, both forfeit their stakes. Identical submission timestamps and expired unaccepted invitations release the stakes. Duel adjustments enter the XP ledger and appear in Practice and applicable rankings.
- **Notifications** shows duel invitations and results, your own achievement unlocks, and changes to your group XP. Opening a group notification requires current membership. [Phase 6 evaluation](docs/evaluation/phase-06/eval.md)

### Try practice rewards

Open **Practice** to see your XP history and weekly goal. **Overview** shows daily solve titles and weekly standings for your selected group. Open **Leaderboard** and choose **Individual** to rank your XP alongside current members of the groups you share, across the full selected period.

- Your first sign-in or active workspace visit each UTC day earns 5 personal XP and counts toward Orbit Cycle. The app shows pop-ups for daily login rewards, verified solves, and new achievements. Coding streaks in Analytics use solve days. A current streak can include a run ending yesterday.
- The Problem of the Day awards 50 XP for a qualifying first solve. The daily quest awards 30 XP for a first solve from the listed Array problems. The weekly quest awards 100 XP for three distinct first solves. The starter LeetCode rotation uses [Two Sum](https://leetcode.com/problems/two-sum/), [Binary Search](https://leetcode.com/problems/binary-search/), and [Best Time to Buy and Sell Stock](https://leetcode.com/problems/best-time-to-buy-and-sell-stock/).
- Daily windows begin at midnight UTC and weekly windows begin at midnight UTC on Monday. A solve at the closing instant belongs to the next window. An in-window solve must be recorded before the cutoff, 24 hours after closing. A qualifying late bonus appears in XP history after delivery. [Phase 5 evaluation](docs/evaluation/phase-05/eval.md)

### Explore personal analytics

Open **Analytics** for solves by difficulty and topic, active coding days, streaks, and XP progression. The activity grid shows the last 365 days in UTC. Select a day to see its count or open its week summary. The analytics summary includes personal login XP.

Topic counts show recorded practice coverage. A first solve can count in several topics, and an unmapped solve still counts in the overall and difficulty totals. The coverage panel identifies missing or incomplete sync history. Historical imports remain gated by verified provider access. [Phase 7 evaluation](docs/evaluation/phase-07/eval.md) · [Phase 0 integration decision](docs/evaluation/phase-00/evidence.md)

Recommendations explain the topic each unsolved problem would help you practice. With fewer than three mapped solves, the catalog favors Easy problems. After three mapped solves, the median recorded difficulty sets the starting point, and the least-practiced topics guide ordering within a difficulty distance. The starter catalog spans Easy to Hard. [Valid Parentheses](https://leetcode.com/problems/valid-parentheses/) · [Merge Intervals](https://leetcode.com/problems/merge-intervals/) · [Trapping Rain Water](https://leetcode.com/problems/trapping-rain-water/)

## HTTPS staging and operations

Use the [deployment plan](docs/deployment-plan.md) to host LeetGrind on Railway with GitHub CI, private MySQL, and separate staging and production environments.

Use the [Phase 4 runbook](docs/phase4-release-runbook.md) to start the HTTPS staging stack, rehearse an upgrade and rollback, run a backup and restore, and manage the sync worker.

The Phase 8 workload compares standings latency and fixture sync freshness on disposable local deployments. [Capacity benchmark and results](docs/evaluation/phase-08/eval.md)

## Develop and test

Install Java 21, Maven, and Node.js 24. Start only the database for live frontend and backend development.

```sh
docker compose up -d --wait db
set -a
. ./.env
set +a
LEETGRIND_BROWSER_ORIGIN=http://127.0.0.1:5173 LEETGRIND_SECURE_COOKIE=false \
  mvn -B -ntp -f backend/pom.xml spring-boot:run
```

In another terminal, start Vite and open **http://127.0.0.1:5173/**. Vite proxies `/api` to the backend on port 8080.

```sh
npm --prefix frontend ci
npm --prefix frontend run dev
```

Run the build and integration tests against a disposable MySQL database. The tests create synthetic accounts in the configured test schema. Set `LEETGRIND_DB_URL` if the database is on a different host or port. The database account needs migration privileges on that schema.

```sh
set -a
. ./.env
set +a
mvn -B -ntp -f backend/pom.xml verify
npm --prefix frontend run build
```

For the browser checks, keep the dev or Compose app running. Install Chromium using Playwright once.

```sh
cd frontend
npx playwright install chromium
npx playwright test
```

Set `LEETGRIND_TEST_URL` to test a running Compose instance instead. The [Phase 1 evaluation](docs/evaluation/phase-01/eval.md) lists the checks this workflow covers.

## Identity design

The `identity` module owns accounts and sessions. The `platform` package contains shared HTTP diagnostics and the bounded background worker executor. The database enforces uniqueness on normalized emails and handles. Group-facing features will use the handle and display name as their public profile fields.

The browser keeps access JWTs in memory. Refresh credentials are opaque and rotate on use. MySQL stores their digests and serializes refresh and logout per session family. Sign-out or detected refresh reuse revokes the family. Cookie-backed routes require the configured origin and `X-LeetGrind-Client: browser`. Secure cookies are the default. The loopback Compose and Vite setup explicitly uses HTTP development cookies. HTTPS deployments should set `LEETGRIND_BROWSER_ORIGIN` to their public HTTPS origin and provide independent database credentials and signing material. The local per-IP auth limit is process-local.

## Practice sync design

`integration` owns provider links, submissions, and sync jobs. `catalog` resolves provider problem IDs, and `progress` owns solves and XP. The worker fetches outside the write transaction. It checks the lease and link version before committing the submission, solve, XP ledger entry, and outbox event. A partial fetch can add verified entries while preserving the previous successful checkpoint. Each link has one queue for manual and scheduled work. The local adapter uses a 15-minute normal poll interval, a 30-second manual cooldown and lease, and bounded retry backoff. [Phase 2 evaluation](docs/evaluation/phase-02/eval.md)

## Private group design

The `groups` module owns private groups, invitation digests, and membership history. It consumes an invitation use in the same transaction as joining and checks current membership before every private read. Group invites use process-local per-account throttling. The weekly query ranks all eligible base XP before pagination and scopes cursors to the group, week, and live or demo view.
