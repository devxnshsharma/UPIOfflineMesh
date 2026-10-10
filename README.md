# UPI Offline Mesh

**Mesh Routed Deferred Payment Settlement over Intermittently Connected Devices** (a Computer Networks project)

A sender with **no internet** encrypts a payment into a packet. The packet is **gossiped phone to phone** through a simulated mesh (store-and-forward, TTL, replication, loss, delay). When any **bridge** phone finds internet it uploads the still-opaque packet to a Spring Boot backend, which authenticates it, de-duplicates it and settles it **exactly once** (one ledger effect per logical payment) in an atomic database transaction.

> **Scope.** A *software simulation*. No real Bluetooth, NPCI, bank or UPI is involved and every account is fictional. The cryptography, idempotency and settlement code are real and tested; the radio, the phones and the bridge-to-backend hop are simulated.
> 
<img width="1999" height="1083" alt="image" src="https://github.com/user-attachments/assets/07d5f0e7-7d13-48dc-9c3f-188b5658d7ab" />
![The Mesh section: create a payment, a full-width network map on a charcoal canvas, and the run controls directly under it](docs/screenshots/02-mesh-topology-settled.jpg)

**Full documentation:** [`UPI_Mesh_Project_Guide.pdf`](UPI_Mesh_Project_Guide.pdf) (same content as [`UPI_Mesh_Project_Guide.docx`](UPI_Mesh_Project_Guide.docx)): architecture, payment lifecycle, networking concepts, cryptography, database, API reference, dashboard manual, test results, troubleshooting, viva preparation and an optional deployment chapter. Hosting notes: [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md).

## Technology stack

Java 17 (compiled with `--release 17`) · Spring Boot 3.3.5 · Spring Data JPA / Hibernate 6.5.3 · H2 2.2.224 (PostgreSQL optional) · Maven 3.9.9 (wrapper) · JUnit 5 · AES-256-GCM, RSA-2048 OAEP (SHA-256), SHA-256 · plain HTML/CSS/JavaScript dashboard (no framework, no build step, no CDN, works offline).

## Prerequisites

* A **JDK 17 or newer** (verified on OpenJDK 26.0.1). Check with `java -version`.
* Internet access **once**, so the Maven wrapper can download Maven and the dependencies.
* Nothing else to run it: no Maven install, no Node.js, no database server, no API keys. (Node 22+ and Chrome are only for the optional browser checks.)

## Quick start

> Paste commands **one at a time and without trailing `# comments`**: interactive zsh does not treat `#` as a comment, so Maven would receive it as an argument.

Open a terminal in the project folder (the one that contains `pom.xml`; the folder name here contains a space, so keep the quotes). On the author's machine:

```bash
cd "/Users/devxnsh/Desktop/code/UPI mesh/UPI-Offline-Mesh"
```

Start the complete application (backend and dashboard are one process):

```bash
./mvnw spring-boot:run
```

Open **http://localhost:8080**. In a second terminal, check health:

```bash
curl -s localhost:8080/api/health
```

Stop with Ctrl+C. Use `PORT=9090 ./mvnw spring-boot:run` for another port, `mvnw.cmd` on Windows, or `sh mvnw ...` if `./mvnw` is not executable.

Build a jar and run it:

```bash
./mvnw clean package -DskipTests
```

```bash
java -jar target.nosync/upi-offline-mesh-1.0.0.jar
```

*(The build folder is `target.nosync/` so macOS iCloud sync of Desktop cannot corrupt class files; it is just a folder name elsewhere.)*

## Tests

```bash
./mvnw test
```

**139 tests**, about 40 seconds: crypto, ingestion, settlement, concurrency, mesh, API, scenarios, experiments and their limits, the hosted access policy, a dashboard contract test, and PostgreSQL schema generation. Last verified run: **139 run, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**. Tests assert on real database state (balances, ledger rows, packet records), not only HTTP responses. Run one class with `./mvnw test -Dtest=ConcurrentIngestionTest`.

**Browser checks** (optional, real Chrome, no npm packages; needs Node 22+ and the jar from the build above):

```bash
node e2e/run.mjs
```

They measure the layout at eleven window sizes (including browser zoom 125 percent; each nav link must land flush on its own section), check the map on every topology with every label on screen at once (no overlap, no clipping, role colours, line patterns, contrast, motion only while the network advances), drive every control against the real backend (create payment, gossip, bridges, replay, settings, reset, all eight scenarios, experiments), check accessibility basics, restart the backend under a live page and exercise the hosted profile. Screenshots and `report.json` go to `target.nosync/e2e`. Latest run: see the guide, chapter 12.

**PostgreSQL** (optional): the whole ingestion and concurrency suites can be run against a real database, which has **not** been done in this repository's environment: `UPI_TEST_PG_URL=postgres://user:password@localhost:5432/upimesh_test ./mvnw test -Dtest='Postgres*IT'` (empty database; see `docs/DEPLOYMENT.md`).

## What you see

One page, one content width: **Overview** (live numbers), **Mesh** (the payment form first, then the full-width network map with zoom, fit and a legend, then the run controls directly under the map so you can advance the network and watch it without scrolling; simulation settings fold away below), **Packets** (opaque ciphertext, fingerprint, holders, history, replay attacks), **Settlement** (ledger and balances), **Prove it** (eight guided scenarios and networking experiments with a chart plus summary and diagnostic tables) and the **Activity log**. The map reads like a network simulator: a blue sender phone, slate relays, teal bridges and an indigo backend; solid slate links between neighbours, amber dashes for a transmission, teal for a bridge link (dashed once it has uploaded), coral for a lost transmission; teal, amber and coral labels for settled, duplicate and rejected. Dashes move only while the network is being advanced (and never with reduced motion), and every colour is also carried by a line pattern, a label or an icon. Choosing a nav link scrolls so that only that section is on screen. If the backend stops answering, the page keeps the last data, marks it out of date and recovers by itself.

## Core demonstration (about 3 minutes)

1. Press **Reset to baseline** twice. Balances: Alice 5000, Bob 1000, Carol 2500, Dave 500.
2. Create payment alice to bob 500, press **Encrypt & inject into mesh**. Nothing settles; the backend is untouched.
3. **Gossip x 4**: the packet reaches all three bridges. **Bridges upload** is disabled: every bridge is offline.
4. Switch **bridge-1** online and press **Bridges upload**: `SETTLED`, Alice 4500, Bob 1500, one ledger row.
5. Switch the other bridges online and upload again: `DUPLICATE`, nothing changes.
6. Under **Prove it**, press **Run all 8 scenarios** (normal, duplicate, tampered, expired, TTL exhaustion, interrupted connectivity, insufficient funds, concurrent updates): 27 checks.

## API (summary)

`GET /api/health` · `/api/server-key` · `/api/accounts` · `/api/transactions` · `/api/stats` · `/api/mesh/state` · `/api/mesh/settings` · `/api/mesh/packets/{id}` · `/api/experiments[/types]` — `POST /api/demo/send` · `/api/demo/replay` · `/api/mesh/gossip` · `/api/mesh/flush` · `/api/mesh/reset` · `/api/bridge/ingest` · `/api/experiments/run` — `PATCH /api/mesh/devices/{id}` · `PUT /api/mesh/settings`. Request and response formats, status codes and examples are in chapter 11 of the guide. Errors share one JSON shape: `{timestamp, status, error, message, details[]}`.

## Data and persistence

Default H2 is **in-memory**: every start is the clean baseline. Accounts are seeded only into an empty database, so a file-backed H2 keeps balances, ledger and idempotency claims across restarts:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:h2:file:./data/upimesh;LOCK_TIMEOUT=10000 --spring.jpa.hibernate.ddl-auto=update"
```

A managed PostgreSQL is configured with one variable, `DATABASE_URL=postgres://user:password@host:5432/name` (see `docs/DEPLOYMENT.md`). The mesh (devices, packets in flight) and the RSA key pair are always in memory. Every setting is in `src/main/resources/application.properties` (`upi.mesh.*`).

## Hosting it (optional, not deployed)

Run it locally for a presentation. For a hosted demo there is a `Dockerfile`, a Render Blueprint (`render.yaml`) and a `hosted` profile: admin token for reset, settings and experiments (the backend checks it; with no token those actions are off), rate limits, request-size and packet caps, browser security headers, no database console. It is still a **shared sandbox without user accounts**; `docs/DEPLOYMENT.md` states what it protects, what it cannot, the environment variables, the database choices (in-memory H2 or PostgreSQL, and what each loses on a restart) and the validation steps. **Nothing has been deployed, and the Docker image has not been built on the development machine.**

## Known limitations

* The radio, phones and mobility are simulated; the simulator calls the ingestion service in-process (the REST endpoint exists and is tested over HTTP).
* Locally nothing is authenticated and the H2 console is open: run it on your own machine. The hosted profile adds an admin token, limits and headers but no user accounts.
* RSA key is regenerated at every start (production needs an HSM/KMS) and the mesh is in memory, so packets from earlier runs are gone after a restart.
* Offline double spending cannot be prevented without a pre-funded wallet; later payments a balance cannot cover are rejected.
* Verified on JDK 26 (compiled for 17), not on a JDK 17 runtime. Browser checks are automated for Chrome only; Safari, Firefox, screen readers and `mvnw.cmd` were not tested. PostgreSQL, the Docker image and Render were not exercised.

More in chapters 15 and 16 of the guide.

## Project layout

```
pom.xml · mvnw · Dockerfile · render.yaml · README.md · UPI_Mesh_Project_Guide.(docx|pdf) · docs/(screenshots, diagrams, DEPLOYMENT.md)
src/main/java/com/demo/upimesh/
  config/ controller/ crypto/ dto/ mesh/ model/ service/
src/main/resources/ application.properties · application-hosted.properties · static/{index.html, app.css, app.js}
src/test/java/com/demo/upimesh/   139 tests
e2e/ cdp.mjs · run.mjs            optional browser checks (Node 22+, Chrome)
```
