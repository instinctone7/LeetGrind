# LeetGrind hosting settings

This plan uses **Vercel for the React frontend**, **Railway for the Spring Boot API**, and **Railway MySQL for application data**. Redis Insight runs on your computer and connects to a separate Redis server if you provision one. The examples use the [LeetGrind GitHub repository](https://github.com/instinctone7/LeetGrind) and its `main` branch.

## 1. Vercel · React frontend

Import the GitHub repository into Vercel and enter these settings. [Vite on Vercel](https://vercel.com/docs/frameworks/frontend/vite) · [Supported Node.js versions](https://vercel.com/docs/functions/runtimes/node-js/node-js-versions)

- **Framework preset** — Vite
- **Root Directory** — `frontend`
- **Node.js version** — `24.x`
- **Install Command** — `npm ci`
- **Build Command** — `npm run build`
- **Output Directory** — `dist` relative to `frontend`
- **Environment Variables** — Leave blank. The app uses relative `/api/v1` URLs. Database credentials and JWT secrets belong on Railway.

Record the stable Vercel production URL. Once Railway gives you the API’s public HTTPS URL, add `frontend/vercel.json` with the following contents and redeploy the frontend. Replace the example Railway hostname. The rewrite keeps API requests on the Vercel origin from the browser’s perspective, which lets the existing session cookie work. The header disables caching of API responses at the Vercel proxy. [Vercel external rewrites and caching](https://vercel.com/docs/routing/rewrites)

```json
{
  "$schema": "https://openapi.vercel.sh/vercel.json",
  "rewrites": [
    {
      "source": "/api/:path*",
      "destination": "https://your-api.up.railway.app/api/:path*"
    }
  ],
  "headers": [
    {
      "source": "/api/:path*",
      "headers": [
        { "key": "x-vercel-enable-rewrite-caching", "value": "0" }
      ]
    }
  ]
}
```

Use a stable Vercel domain for staging as well if testing sign-in there. Spring Boot accepts one exact browser origin per environment, so that staging frontend needs a matching Railway staging API and rewrite target.

## 2. Railway · Spring Boot API

In the Railway project with your MySQL service, connect the GitHub repo and name the application service `leetgrind-app`. [Railway Dockerfile builds](https://docs.railway.com/builds/dockerfiles) · [Railway health checks](https://docs.railway.com/deployments/healthchecks)

- **Root Directory** — Repository root `/`
- **Builder** — Dockerfile, detected from the root `Dockerfile`
- **Build Command / Start Command** — Leave overrides blank. The Dockerfile builds the combined image and starts `java -jar /app/leetgrind.jar`.
- **Output** — Docker image containing `/app/leetgrind.jar`. Railway has no output directory to enter.
- **Public HTTPS domain target port** — `8080`
- **Healthcheck Path** — `/actuator/health/readiness`
- **Replicas** — Start with one always-on replica.

Paste these into the Railway app service’s Variables tab. Generate a unique signing key with `openssl rand -base64 32`. Replace the example origin with the stable **Vercel** URL, including `https://` and without a trailing slash. The MySQL service name in the references must match the name Railway shows. [Railway service references](https://docs.railway.com/variables) · [Hosted Spring profile](../backend/src/main/resources/application-hosted.yml)

```dotenv
SPRING_PROFILES_ACTIVE=hosted
PORT=8080
LEETGRIND_DB_URL=jdbc:mysql://${{MySQL.MYSQLHOST}}:${{MySQL.MYSQLPORT}}/${{MySQL.MYSQLDATABASE}}
LEETGRIND_DB_USER=${{MySQL.APP_DB_USER}}
LEETGRIND_DB_PASSWORD=${{MySQL.APP_DB_PASSWORD}}
LEETGRIND_JWT_SECRET=replace-with-generated-base64-key
LEETGRIND_BROWSER_ORIGIN=https://your-frontend.vercel.app
LEETGRIND_SIGNUP_MODE=CLOSED
```

For a controlled signup test, change `LEETGRIND_SIGNUP_MODE` to `PILOT` and add `LEETGRIND_PILOT_EMAILS=tester@example.test` with your approved test address. The hosted profile enables secure cookies and disables demo fixtures and LeetCode previews by default. To enable solve sync, configure the [hosted profile service](deployment-plan.md#leetcode-launch-scope) and verify your saved LeetCode handle in Profile.

## 3. Railway MySQL · application database

Add the MySQL database service first and name it `MySQL`. Verify the image is MySQL 8.4 to match local development and CI. If the template ships a different version, provision a new `mysql:8.4` image service before importing any data. [Railway MySQL](https://docs.railway.com/databases/mysql) · [MySQL Docker initialization](https://dev.mysql.com/doc/refman/8.4/en/docker-mysql-more-topics.html)

- **Source** — MySQL template or `mysql:8.4` Docker image
- **Build Command / Start Command** — Leave blank. The image starts MySQL.
- **Output / storage** — No build output. Attach a persistent volume at `/var/lib/mysql` and schedule daily backups.
- **Network** — Private, in the same Railway environment and region as the API. MySQL listens internally on `3306`.
- **Environment with the template** — Keep the generated `MYSQLHOST`, `MYSQLPORT`, and `MYSQLDATABASE` values. Create the dedicated database user using the [MySQL account setup](deployment-plan.md#mysql-account-setup), then add `APP_DB_USER=leetgrind_app` and `APP_DB_PASSWORD` with that user’s password to the MySQL service. Spring Boot runs Flyway migrations on startup.

For a fresh image service, the following variables initialize a schema and dedicated app account on an empty volume. If the volume already contains data, use the database account setup procedure to create or update credentials. Keep the root password separate from the application password.

```dotenv
MYSQL_DATABASE=leetgrind
MYSQL_USER=leetgrind_app
MYSQL_PASSWORD=replace-with-app-db-password
MYSQL_ROOT_PASSWORD=replace-with-different-admin-password
MYSQLHOST=${{RAILWAY_PRIVATE_DOMAIN}}
MYSQLPORT=3306
MYSQLDATABASE=${{MYSQL_DATABASE}}
APP_DB_USER=${{MYSQL_USER}}
APP_DB_PASSWORD=${{MYSQL_PASSWORD}}
```

## 4. Redis Insight · optional local administration

LeetGrind currently uses MySQL for durable data and in-process rate limiting. Redis Insight is a GUI and requires a **separate Redis server**. If you want to try the GUI with Railway Redis, add a private Redis template service named `Redis`. It uses a prebuilt image and has no build command or output directory. Keep the template’s generated connection details, including `REDISUSER` and `REDISPASSWORD`. Leave LeetGrind’s environment variables as shown above until it has a Redis integration. [Railway Redis](https://docs.railway.com/databases/redis)

Use Redis Insight Desktop for the SSH tunnel. From a Railway CLI linked to the right project, run the following, substituting the Railway environment name if needed. Keep the terminal open and enter the printed host, port, username, and password in Redis Insight. Use the Redis server’s TLS setting. [Railway CLI database tunneling](https://docs.railway.com/cli/connect)

```sh
railway connect Redis --environment staging --tunnel-only --port 16379
```

If you prefer to run Redis Insight itself in Docker, these are the local settings. For a cloud database reached through a loopback SSH tunnel, the Desktop app is the straightforward option because it shares the host network. [Redis Insight Docker installation](https://redis.io/docs/latest/operate/redisinsight/install/install-on-docker/) · [Redis Insight environment variables](https://redis.io/docs/latest/operate/redisinsight/configuration/)

- **Image / Build** — `redis/redisinsight:latest`, with no build or start override
- **Environment** — Docker defaults `RI_APP_HOST=0.0.0.0` and `RI_APP_PORT=5540`. Optional `RI_ENCRYPTION_KEY` encrypts stored connection details and should stay the same for the life of the volume.
- **Output / storage** — No build output. Local web UI at `http://127.0.0.1:5540`, persistent volume mounted at `/data`.

```sh
docker run -d --name redisinsight \
  -p 127.0.0.1:5540:5540 -v redisinsight:/data redis/redisinsight:latest
```

## Deployment order and verification

1. **Reserve the Vercel URL.** Import the frontend project to get its stable deployment URL. Sign-in is ready to test after the rewrite is in place.
2. **Deploy MySQL and Railway.** Start MySQL, configure the dedicated account, and deploy the API using the Vercel URL as `LEETGRIND_BROWSER_ORIGIN`. The API’s readiness URL should respond with `{"status":"UP"}`.
3. **Deploy the Vercel rewrite.** Set the Railway API URL in `frontend/vercel.json` and redeploy Vercel. A signed-out request to `https://your-frontend.vercel.app/api/v1/leaderboard` should return `401`. If it returns the Vercel page or `404`, check the rewrite and its location.
4. **Check authentication end to end.** Sign in on the Vercel URL and reload the page. Verify the session persists, sign-out clears it, and authenticated API responses are uncached. A `403 invalid_origin` on auth routes means the browser origin and `LEETGRIND_BROWSER_ORIGIN` differ.
5. **Connect Redis Insight if provisioned.** Use the private tunnel and check that the GUI connects to Redis. MySQL and the LeetGrind release can be validated independently.
