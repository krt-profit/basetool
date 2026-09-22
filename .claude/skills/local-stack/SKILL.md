---
name: local-stack
description: Start, configure or tear down the Profit Basetool local Docker Compose stack (dev, prod-equivalent, or the isolated test stack with throwaway credentials). Use when running the app locally, bringing up Postgres/Keycloak/Redis dependencies, looking up host ports, or setting up the test stack to verify a change.
---

# Local stack

Use Docker Compose profiles:

```bash
docker compose --profile dev up -d db-backend-dev db-keycloak-dev keycloak-dev redis-dev   # deps only, run apps locally
docker compose --profile dev up -d                                                          # full dev stack with host port exposure
docker compose --profile prod up -d                                                         # prod-equivalent stack behind the nginx edge (needs the prod .env)
docker compose --env-file .env.test -f docker-compose.yml -f docker-compose.test.yml \
    --profile dev up -d                                                                     # isolated test stack with throwaway credentials
docker compose --env-file .env.test -f docker-compose.yml -f docker-compose.test.yml \
    -f docker-compose.android.yml --profile dev up -d                                       # ... and this one WHENEVER the client is the Android emulator
```

Further overrides, layered on top in this order when needed: `docker-compose.build.yml` (build the
images from this checkout, tag `:local`, instead of pulling `:stable`), `docker-compose.e2e.yml`
(project-scoped volumes, what `:frontend:e2eTest` uses) and `docker-compose.localtest.yml` (append
**last**: republishes Keycloak for a manual host browser and switches the UEX / SC-wiki syncs off so
seeded data stays put). Each file's header carries its exact command line.

Production does **not** run Compose: it runs rootless Podman + Quadlet units generated from these
files (`scripts/generate-quadlet.py` → `quadlet/`). A change to a service in `docker-compose*.yml`
therefore needs the units regenerated in the same change (`python3 scripts/generate-quadlet.py`;
the `quadlet-drift` check in `repo-lint.yml` runs `--check`).

Host ports (dev profile only, bound to `127.0.0.1`): backend `11261`, frontend `18081`, ingest `11262`, Keycloak `18080`, backend DB `15432`, Keycloak DB `15433`, Redis `6379`. A `.env` at repo root is required for the regular dev/prod profiles (see README for keys). The isolated test stack instead reads `.env.test` and a stripped `realm-export.json`; its TLS material is the **committed** throwaway keystore under `docker/test-tls/` (password `basetool-test`, ADR-0139), bound by a hardcoded path — nothing to generate. See the README's *Running the local test stack* section for setup, and never substitute production artifacts for those.

The backend always serves HTTPS (`server.ssl.enabled: true`, key alias `basetool`); the frontend reaches it at `https://backend:11261` by default (`BACKEND_URL`), so a frontend run from Gradle against a backend run from Gradle needs `BACKEND_URL=https://localhost:11261`. There is no Swagger UI — the OpenAPI documents are served at `https://localhost:11261/v3/api-docs` (backend) and `https://localhost:11262/v3/api-docs` (ingest) in the `dev`/`test` profiles only (disabled in `prod`); the committed `backend/src/main/resources/api/openapi.json` and `ingest/src/main/resources/api/openapi.json` are the API-documentation artifacts.

## Driving the Android emulator against this stack

**Always layer `docker-compose.android.yml` on top when the client is the app.** Without it the
stack pins `KC_HOSTNAME=host.docker.internal` — a name that containers and the host browser both
resolve and that **the emulator resolves not at all**: it reaches the host only as `10.0.2.2`, and
Play-store system images cannot be rooted to add a hosts entry. The login then dies *after* the
Keycloak form with `DNS_PROBE_FINISHED_NXDOMAIN`, which looks like a broken realm rather than the
topology mismatch it is.

The override pins the issuer to `127.0.0.1:18080` — which is what the app's `dev` flavour already
expects — republishes Keycloak and the backend on all interfaces, and gives the backend a
split-horizon `KEYCLOAK_JWK_SET_URI` so it can still validate tokens whose issuer names a host that
is *itself* from inside the container. The file's own header explains each part.

Three things to know before reaching for it:

- The **web frontend's own login stops working** while it is in effect. This is an app-work
  override, not a general one.
- The emulator needs the reverse tunnels or nothing reaches the host:
  `adb reverse tcp:18080 tcp:18080`, then `tcp:11261` and `tcp:18081`. They do **not** survive an
  emulator restart, and their absence looks exactly like "you are offline".
- A refresh token from a **previous** stack survives on the device and fails against the new realm
  (`REFRESH_TOKEN_ERROR / Invalid refresh token`). After a `down --volumes`, clear the app first:
  `adb shell pm clear de.greluc.krt.profit.basetool.android.dev` — and re-apply the German locale
  afterwards, because `pm clear` drops it.

## Credentials

**Never use production / real credentials in a local stack** — this is the hard rule from the
root `CLAUDE.md` Testing section. Always source `.env.test` (never `.env`), point Docker Compose
at `--env-file .env.test`, and tear the stack down with `down --volumes` after the verification.

The dev-profile Postgres/Redis data lives in project-prefixed **named volumes**, so worktrees do not
share a database and `down --volumes` really does reset it. A stack that fails with
`password authentication failed` is running against a data directory some earlier run initialised
with different credentials — tear it down with `--volumes` rather than editing `.env.test` to match.
The `*-dev` services also run the **`dev`** Spring profile, which is load-bearing: under `prod` they
would serve Actuator on an internal-only management port while the image's `HEALTHCHECK` keeps
probing the app port, so the container answers 404 to its own probe and stays `unhealthy` forever
(ADR-0090/ADR-0134).
