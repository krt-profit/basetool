---
name: local-stack
description: Start, configure or tear down the Profit Basetool local Docker Compose stack (dev, prod-equivalent, or the isolated test stack with throwaway credentials). Use when running the app locally, bringing up Postgres/Keycloak/Redis dependencies, looking up host ports, or setting up the test stack to verify a change.
---

# Local stack

Use Docker Compose profiles:

```bash
docker compose --profile dev up -d db-backend-dev db-keycloak-dev keycloak-dev redis-dev   # deps only, run apps locally
docker compose --profile dev up -d                                                          # full dev stack with host port exposure
docker compose --profile prod up -d                                                         # prod-equivalent stack behind nginx-proxy-manager
docker compose --env-file .env.test -f docker-compose.yml -f docker-compose.test.yml \
    --profile dev up -d                                                                     # isolated test stack with throwaway credentials
```

Host ports (dev profile only): backend `11261`, frontend `18081`, Keycloak `18080`, backend DB `15432`, Keycloak DB `15433`, Redis `6379`, NPM admin `10081`. A `.env` at repo root is required for the regular dev/prod profiles (see README for keys). The isolated test stack instead reads `.env.test` plus a locally generated `keystore.p12` and a stripped `realm-export.json` — see the README's `Running the Local Test Stack` section for setup, and never substitute production artifacts for those.

The backend serves HTTPS with a self-signed cert (`keystore.p12`, password `changeit`); the frontend talks to `https://backend:11261` in prod and `http://localhost:11261` (overridable via `BACKEND_URL`) in dev. There is no Swagger UI — the OpenAPI document is served at `https://localhost:11261/v3/api-docs` in the `dev`/`test` profiles only (disabled in `prod`); the committed `backend/src/main/resources/api/openapi.json` is the single API-documentation artifact.

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
