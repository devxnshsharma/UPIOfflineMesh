# Deploying UPI Offline Mesh (optional)

This is a **teaching prototype with fictional money**. The safest way to present it is locally (`./mvnw spring-boot:run`, see the README). This page prepares an optional hosted demo, for example on [Render](https://render.com), and says plainly what is and is not safe about it.

**Status.** Nothing has been deployed from this repository. The `Dockerfile` and `render.yaml` have not been built or run on a hosting service, and the Docker image has not been built on the development machine (its Docker daemon was not running). What *was* verified is listed at the end.

## 1. Choose how to show it

| Option | What it is | Fit |
|---|---|---|
| **Local** | `./mvnw spring-boot:run` on your laptop, open `http://localhost:8080` | Best for a presentation or viva. Nothing is exposed. |
| **Hosted, unlisted** | Render web service with the `hosted` profile and an admin token. Anyone who knows the URL can open it. | For showing it to a few people. Render's free plan cannot restrict by IP (that needs a Scale plan), so treat the URL as public. |
| **Hosted, public** | Same, advertised openly | Only as a **shared sandbox** you are happy to see abused. Read section 2 first. |

## 2. What the hosted profile protects, and what it cannot

`SPRING_PROFILES_ACTIVE=hosted` (set by the Dockerfile and `render.yaml`) turns on, from `application-hosted.properties`:

* **Admin token** for the three actions that destroy shared state or burn CPU: *reset*, *simulation settings* and *running an experiment*. The backend checks `Authorization: Bearer <token>` (constant-time comparison). With no `ADMIN_TOKEN` set those three actions are switched off for everyone. The dashboard shows an "Admin access" box; the token stays in that browser tab only.
* **Rate limits** per client address: 120 writes and 900 reads per minute by default (`WRITES_PER_MINUTE`, `READS_PER_MINUTE`), answered with `429` and `Retry-After`.
* **Bounded work**: request bodies over 64 KB are refused (also while streaming), an experiment may run at most 200 simulations and only one at a time, and the live mesh refuses new payments beyond 200 packets until the owner resets.
* **Browser protections** on every response: a strict Content-Security-Policy (no inline script or style, no third-party origin), `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Strict-Transport-Security` over https, `Cache-Control: no-store` on the API. Writes whose `Origin` is another website are refused. No CORS permission is ever granted.
* **No developer database console** (`spring.h2.console.enabled=false`) and no stack traces, exception messages or private key material in error responses. The RSA private key exists only in server memory.

What it **cannot** fix, so a public deployment stays a limited sandbox:

* There are no user accounts. Everyone shares one mesh, one ledger and four fictional accounts; any visitor can move demo balances (the real `/api/bridge/ingest` endpoint is open by design) and can fill the ledger until the owner resets.
* The admin token is one shared secret with no expiry, rotation or audit trail.
* The rate limiter lives in one process. It cannot stop a distributed flood; that needs the platform's protection or a CDN.
* In-memory state (mesh, packets, and the default H2 database) disappears when the service restarts or, on Render's free plan, spins down after 15 minutes without traffic.

## 3. Environment variables

| Variable | Required | Meaning |
|---|---|---|
| `PORT` | set by Render | The port to listen on (the app reads it; default 8080 locally). |
| `SPRING_PROFILES_ACTIVE` | yes, `hosted` | Switches on the policy above. The Dockerfile sets it. |
| `ADMIN_TOKEN` | strongly advised | Admin token. Generate with `openssl rand -base64 32`. Render prompts for it when the Blueprint is first created (`sync: false`); it is never in the repository. |
| `DATABASE_URL` | optional | `postgres://user:password@host:5432/name` of a managed PostgreSQL. Without it the app uses in-memory H2. |
| `WRITES_PER_MINUTE`, `READS_PER_MINUTE` | optional | Per-client limits (defaults 120 and 900). |
| `JAVA_TOOL_OPTIONS` | set in the Dockerfile | Memory flags that keep the JVM inside 512 MB. |

## 4. Which database

| Choice | Persists across restarts? | Cost and caveats |
|---|---|---|
| **In-memory H2** (default) | No. Every start is the clean baseline. | Free, nothing to set up. Fine for a demo; say so to viewers. |
| **H2 file** | Only with a persistent disk attached to the instance | Render persistent disks are a paid feature and tie the service to one instance. Not recommended. |
| **Managed PostgreSQL** (`DATABASE_URL`) | Yes, balances, ledger and idempotency records | Render Postgres: a *free* database expires 30 days after creation (14 days of grace, then it is deleted) and only one free database is allowed per workspace; paid instances persist. Recommended whenever data must outlive a restart. |

**Is the code PostgreSQL-ready?** Mostly by design: plain JPA, `NUMERIC(19,2)` money, every exactly-once guard is a `UNIQUE` constraint, row locks use `SELECT ... FOR UPDATE`, no H2-only SQL. `DatabaseUrlPostProcessor` turns `DATABASE_URL` into the JDBC settings, keeps the schema between restarts (`ddl-auto=update`) and sets `lock_timeout` like the H2 URL's `LOCK_TIMEOUT`. A unit test generates the schema for the PostgreSQL dialect offline. **It has not been run against a real PostgreSQL server here.** To do that on your machine, with an empty database:

```bash
UPI_TEST_PG_URL=postgres://user:password@localhost:5432/upimesh_test ./mvnw test -Dtest='Postgres*IT'
```

That runs the whole ingestion and concurrency suites (validation, atomic settlement, duplicates, nonce reuse, retryable failures) against PostgreSQL.

`ddl-auto=update` is acceptable for a demo; a real deployment would use migrations (for example Flyway) and a reviewed schema.

## 5. Keys and restarts

The server's RSA key pair is generated in memory at every start. The mesh (devices, packets in flight) is also in memory, so a restart drops all packets anyway and a durable key would not rescue any of them. Settled payments and idempotency records *do* persist with PostgreSQL, and they do not depend on the key. If a real bridge phone held a packet encrypted to the old public key, the new instance would reject it as `DECRYPTION_FAILED`: the sender would have to create it again. A production system would keep the key in a key management service or secret manager (never in the repository, the browser code or the documentation) and persist the mesh; neither is implemented because they are outside this prototype.

## 6. Deploy on Render, step by step

1. Put the repository on GitHub (Render deploys from a Git repository). Do not commit secrets or a `data/` folder; `.gitignore` and `.dockerignore` already exclude build output.
2. In Render choose **New, Blueprint**, pick the repository. It reads `render.yaml`: a Docker web service, health check `/api/health`, manual deploys.
3. Enter `ADMIN_TOKEN` when asked. For PostgreSQL, first uncomment the `DATABASE_URL` entry and the `databases:` block in `render.yaml`.
4. Deploy. On the free plan (0.1 CPU) the first start can take a minute or two.
5. Validate the hosted URL (replace the host):

```bash
curl -s https://YOUR-SERVICE.onrender.com/api/health
```

Expect `"status":"UP","database":"UP"`, `"mode":"hosted"`, `"adminRequired":true`, `"h2Console":false`.

```bash
curl -sI https://YOUR-SERVICE.onrender.com/ | grep -i -E "content-security-policy|x-frame-options|strict-transport"
```

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST https://YOUR-SERVICE.onrender.com/api/mesh/reset
```

Expect `401` without the token, `200` with `-H "Authorization: Bearer YOUR_TOKEN"`. `https://YOUR-SERVICE.onrender.com/h2-console` must not open. Then open the dashboard, create a payment, gossip, switch a bridge online and upload; and enter the token under "Admin access" to run the guided scenarios.

Without a Blueprint: **New, Web Service**, runtime **Docker**, health check path `/api/health`, and the environment variables above.

## 7. Common problems

| Symptom | Cause and fix |
|---|---|
| Deploy times out or the service restarts | The free instance is small. Wait for the slow first start, or pick a larger plan. |
| Dashboard shows "reconnecting" after a while | The free service spun down; the first request wakes it in about a minute and the in-memory state starts from the baseline. |
| `401 ADMIN_REQUIRED` on reset, settings, experiments | Enter the token under "Admin access". Check there is no trailing space. |
| `403 ADMIN_DISABLED` | `ADMIN_TOKEN` is not set on the service. |
| `429 RATE_LIMITED` | Over the per-minute budget (a full guided-scenario run uses about 100 writes). Wait for the minute to pass or raise `WRITES_PER_MINUTE`. |
| `409` "The mesh already holds 200 packets" | Reset (admin) to clear the demo. |
| Startup error "DATABASE_URL is not a valid URL" | Percent-encode special characters in the password. The error never prints the URL. |
| `UnsupportedClassVersionError` | The runtime image is older than Java 17. Keep `eclipse-temurin:17-jre`. |

## 8. What was verified for this deployment path

Automated, on the development machine: the hosted profile's admin protection, fail-closed behaviour, headers, no-CORS, error hygiene, rate limit, body cap, experiment budget and single-run guard (unit and Spring tests); the `DATABASE_URL` parser; PostgreSQL schema generation; and the packaged jar started with the hosted profile and driven by a real browser (admin token flow, headers, rate-limit message). **Not verified:** the Docker image build, a deployment on Render, a real PostgreSQL server, JDK 17 as the runtime (the build targets 17 and ran on JDK 26).
