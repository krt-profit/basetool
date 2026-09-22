# OAuth2 confidential-client migration (audit finding M-6)

> **Doc type:** Implementation runbook for [ADR-0001](adr/0001-frontend-confidential-oauth2-client.md).
> The *decision* and its rationale live in the ADR; this document is the step-by-step *how*.
> Registered in [`docs/specs/INDEX.md`](specs/INDEX.md). Last reviewed: 2026-09-22 — every file,
> class and setting named below re-checked against the repository; commands rewritten for the
> rootless-Podman production host.

**Status:** open — neither the code part nor the Keycloak part has been carried out. On 2026-09-22
`frontend/src/main/resources/application.yml` still registers `basetool-frontend` with
`client-authentication-method: none`, the realm reference still has `publicClient: true`, and
ADR-0001 is *Accepted — implementation pending*.
**Audit finding:** M-6 (security audit 2026-05-20).
**Severity:** Medium (defence in depth — no directly exploitable vector).

## What this is about

The frontend Spring Boot server is currently registered with Keycloak as a **public OAuth2 client**:

```yaml
# frontend/src/main/resources/application.yml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: basetool-frontend
            client-authentication-method: none   # ← public client, PKCE only
            scope: openid, profile, email, roles
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
```

"Public" means that at the token endpoint the frontend sends only the authorization code and the
PKCE verifier — **no client secret**. That is RFC-conformant (RFC 8252, RFC 9700 BCP) and the norm
for SPAs and mobile apps, which cannot keep a secret.

This frontend is not an SPA: it is a server-side Thymeleaf application, i.e. a **confidential**
component that can hold a secret in a server environment variable. Not doing so is a missed
defence-in-depth opportunity.

> [!note] What is already in place — Keycloak requires PKCE `S256`
> Since the Keycloak hardening run (step 6, applied by 2026-09-09 —
> [`KEYCLOAK_HARDENING_RUNBOOK.md`](KEYCLOAK_HARDENING_RUNBOOK.md)) the realm **requires** `S256`
> on `basetool-frontend`, so an authorization request without a PKCE challenge is refused. That
> closes the PKCE downgrade; it does not add the second factor at the token endpoint this migration
> is about, so **M-6 stays open** until the steps below are done.

## What the secret adds

With PKCE alone: whoever captures the authorization code **and** holds the PKCE verifier can redeem
it. The code comes back as a `?code=…` query parameter on `/login/oauth2/code/keycloak`, so it is
briefly visible in the path of a TLS-terminated request.

Realistic interception vectors:

- **A compromised TLS-terminating reverse proxy** (the edge nginx on the host). Whoever has access
  there sees the code in clear text.
- **Server-side request forgery** in a neighbouring application that can reach the edge's access
  logs.
- **A misconfigured open redirect** in the frontend that sends the authorization response to an
  attacker's host.
- **Browser-side malware** reading the redirect parameter.

With a client secret as well: **even a captured code cannot be redeemed** — the token endpoint
also demands the secret, which only the real frontend server knows. An attacker would additionally
need the production `.env`, and at that point they have bigger levers anyway.

PKCE stays active in the new mode: the pattern is **PKCE + client secret**, not one instead of the
other. That is the defence-in-depth gain — a single compromised layer is no longer enough.

## Why it did not ship with the audit-fix PR

The migration cannot be done atomically in one commit:

1. **Code change in the repository** (`application.yml`, `docker-compose.yml` and the Quadlet
   environment template generated from it, `.env.example`) — arrives by PR.
2. **Keycloak Admin Console changes** — switch the client from public to confidential, generate a
   secret. Cannot be done by PR.
3. **Update the production `.env`** with the new secret — operator work, outside the repository.
4. **Roll out the release** carrying the code change.

Between steps 2 and 4 new logins fail. That needs a maintenance window or a carefully sequenced
rollout, which is why the finding was left as a Medium follow-up rather than folded into the
2026-05-20 audit fix.

---

## Work list

### Part A — code (repository PR)

**A.1 — `frontend/src/main/resources/application.yml`**

In `spring.security.oauth2.client.registration.keycloak`:

```diff
 keycloak:
   client-id: basetool-frontend
-  client-authentication-method: none
+  client-authentication-method: client_secret_basic
+  client-secret: ${KEYCLOAK_FRONTEND_CLIENT_SECRET}
   scope: openid, profile, email, roles
   authorization-grant-type: authorization_code
   redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
```

`client_secret_basic` sends the secret in an HTTP Basic `Authorization` header to the token
endpoint and is Keycloak's default; `client_secret_post` (secret in the body) works too.

**PKCE keeps working without further configuration.** Spring Security 7 (the version Spring Boot
4.1 brings) applies PKCE to every client whose `ClientRegistration.ClientSettings.requireProofKey`
is true, and that setting **defaults to true** — read from `spring-security-oauth2-client` 7.1.1
on 2026-09-22. (Earlier revisions of this runbook said Spring sends PKCE only for public clients;
that was Spring Security 6 behaviour and is no longer the case.) Keycloak already requires `S256`,
so a frontend that stopped sending PKCE would fail every login loudly rather than silently losing
the factor.

**A.2 — `docker-compose.yml`, then the generated Quadlet template**

In the `x-frontend: &frontend-template` definition's `environment:` map (like
`KEYCLOAK_ADMIN_CLIENT_SECRET` in `x-backend`), add:

```yaml
environment:
  # ... existing vars ...
  KEYCLOAK_FRONTEND_CLIENT_SECRET: ${KEYCLOAK_FRONTEND_CLIENT_SECRET:?KEYCLOAK_FRONTEND_CLIENT_SECRET must be set in .env}
```

The `:?…` syntax makes Compose abort with a clear message when the secret is missing from `.env` —
the same pattern as the other secrets. Then regenerate the production units, which are derived
from the compose file and drift-checked in CI (`repo-lint.yml`):

```bash
python scripts/generate-quadlet.py          # writes quadlet/env.d/frontend.env.tmpl
python scripts/generate-quadlet.py --check  # must report no drift
```

On the production host `render-env-d.py` applies the same `:?` rule: it refuses to render
`env.d/frontend.env` while `.env` lacks the variable, so the release must not be promoted before
the secret is on the host (Part C).

**A.3 — `.env.example`**

A new entry with rotation instructions, next to `KEYCLOAK_ADMIN_CLIENT_SECRET`:

```bash
# Keycloak basetool-frontend OAuth2 client secret (Spring OAuth2 client login).
# Generate / rotate in the Keycloak admin console:
#   Realm "iri" -> Clients -> basetool-frontend -> Credentials -> Regenerate Secret.
KEYCLOAK_FRONTEND_CLIENT_SECRET=CHANGE_ME
```

**A.4 — `frontend/src/main/resources/application-test.yml`**

The `@SpringBootTest` contexts boot the OAuth2 client. Once `client-secret:
${KEYCLOAK_FRONTEND_CLIENT_SECRET}` has no fallback, the context fails to start without a
placeholder. The test profile already overrides the registration (`client-id: test-client`,
issuer `http://keycloak.example.com/realms/test`); add the secret beside it:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: test-client
            client-secret: test-client-secret
            authorization-grant-type: authorization_code
```

The test issuer is never called (the OAuth2 login flows run against mocks); the secret only has to
resolve as a property.

**A.5 — no default in `application.yml`; a dev override instead**

A fallback such as `${KEYCLOAK_FRONTEND_CLIENT_SECRET:dev-secret-placeholder}` in `application.yml`
would make `./gradlew :frontend:bootRun` convenient but would also mask a missing production value.
Keep `application.yml` strict (like `SERVER_SSL_KEY_STORE_PASSWORD`) and put the fallback into
`frontend/src/main/resources/application-dev.yml` only, overriding just that one property:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-secret: ${KEYCLOAK_FRONTEND_CLIENT_SECRET:dev-secret-placeholder}
```

The realm a local stack imports (`keycloak-dev` runs `start-dev --import-realm` against the
gitignored `realm-export.json` at the repository root) must then carry `basetool-frontend` as a
confidential client with a matching synthetic secret, or local logins fail. The committed E2E realm
(`frontend/src/e2e/resources/realm-export.e2e.json`) and the E2E stack's frontend environment need
the same change in the PR.

**A.6 — Tests**

- `frontend/src/test/java/de/greluc/krt/profit/basetool/frontend/config/SecurityConfigTest.java` —
  adjust any assertion that expects `client-authentication-method: none` or the absence of a
  `client-secret`.
- `frontend/src/test/java/de/greluc/krt/profit/basetool/frontend/config/RoleHierarchyTest.java` —
  loads a context; should pass unchanged with the placeholder from A.4, verify.
- Add a pinning test (e.g. beside `frontend/src/test/java/de/greluc/krt/profit/basetool/frontend/SecurityHeadersTest.java`,
  or a new `OAuth2ClientConfigurationTest`) asserting through the `ClientRegistrationRepository`
  that the `keycloak` registration uses `CLIENT_SECRET_BASIC`, has a non-empty secret and
  `requireProofKey == true` — so a regression to the public-client path, or losing PKCE, breaks the
  build.

**A.7 — Documentation in the same PR**

`CHANGELOG.md` (a short entry under `## [Unreleased]`), the new variable in `README.md`'s env-var
section, ADR-0001's status line (*Accepted* once shipped), `docs/keycloak/README.md`'s client
table, and the knowledge vault.

### Part B — Keycloak (operator)

**B.1 — Open the Keycloak Admin Console**

Production: `https://profit-base.online/auth/admin/` (moved 2026-09-13, ADR-0166). Sign in with an
admin account of the `master` realm.

**B.2 — Switch `basetool-frontend` to confidential**

Realm dropdown → **`iri`** → **Clients** → **basetool-frontend** → **Settings**:

- **Client authentication**: `Off` → `On`.
- **Authorization** stays `Off` (Keycloak Authorization Services are not used).
- **Authentication flow** unchanged: *Standard flow* `On`, *Direct access grants* `Off`,
  *Implicit flow* `Off`, *Service accounts roles* `Off`.
- **Save**. A **Credentials** tab appears.

**New logins fail from this moment until C.4 completes.**

**B.3 — Generate the client secret**

**Credentials** tab: *Client Authenticator* stays **Client ID and Secret**; **Regenerate** next to
the secret field, confirm, and copy the value — it is `KEYCLOAK_FRONTEND_CLIENT_SECRET`. Put it
straight into the production `.env` (C.3) and a password manager; never into a chat, a ticket or a
screenshot.

**B.4 — PKCE: nothing to do**

*Require PKCE* / *PKCE Method* `S256` is already set on this client (hardening step 6). Verify it
is still there after B.2; the switch to confidential does not touch it.

**B.5 — The host's `realm-export.json`**

Production Keycloak runs `start` **without** `--import-realm` (`quadlet/systemd/keycloak.container`),
so the mounted `/var/iri/code/realm-export.json` is never re-imported and cannot flip the client
back: the Keycloak database is the source of truth, and a disaster restore uses `keycloak.dump`
([`docs/backup.md`](backup.md)). Still update the host file's `basetool-frontend` entry
(`"publicClient": false`, `"clientAuthenticatorType": "client-secret"`) so a realm rebuilt from it
does not quietly reintroduce a public client — the secret itself need not go into it. Afterwards
regenerate the sanitized reference ([`docs/keycloak/README.md`](keycloak/README.md)).

### Part C — rollout sequence

The production host is pull-based: a promoted release is applied by `deploy.sh` on its timer
([`docs/deployment.md`](deployment.md#promoting-to-production)). Merge Part A, but **do not promote
it** before the window.

**C.1 — Announce a maintenance window.** Plan 5–10 minutes of login downtime; existing sessions
keep working.

**C.2 — Switch Keycloak** (B.2–B.3).

**C.3 — Put the secret on the host:**

```bash
sudo cp -p /var/iri/code/.env /var/iri/code/.env.backup-$(date +%Y%m%d-%H%M%S)
sudo -u deploy "${EDITOR:-vi}" /var/iri/code/.env    # add KEYCLOAK_FRONTEND_CLIENT_SECRET=<value from B.3>
```

**C.4 — Promote the release and apply it now** rather than waiting for the next tick:

```bash
gh workflow run promote.yml -f version=<the release carrying Part A>
sudo systemctl start iri-deploy.service      # on the host, once the promotion has finished
tail -n 50 /var/log/iri-deploy.log
```

The deploy renders `env.d/frontend.env` from `.env` (it refuses, naming the variable, if C.3 was
missed) and recreates the frontend. An `invalid_client` / *Invalid client credentials* in the
frontend's log (`sudo -u iri podman logs frontend`) means the secret in `.env` does not match
Keycloak's.

**C.5 — Smoke test**

- Private browser window → `https://profit-base.online` → log in → back on the start page with a
  valid session.
- Keycloak Admin Console → realm `iri` → **Events** → the latest `CODE_TO_TOKEN` event for
  `basetool-frontend`: its `client_auth_method` detail names the client-secret authenticator. (User
  events are stored since hardening step 4, so this check works.)

**C.6 — Rollback**

1. Keycloak: `basetool-frontend` → Settings → **Client authentication** `Off` → Save.
2. Re-promote the previous release (`promote.yml`), and start `iri-deploy.service` again.
3. Leave `KEYCLOAK_FRONTEND_CLIENT_SECRET` in `.env`; the previous release's template does not
   reference it, and it is needed again for the next attempt.

That returns the system to public client + PKCE, functionally identical to the pre-migration state.

---

## Validation after a successful rollout

- [ ] `sudo grep -c '^KEYCLOAK_FRONTEND_CLIENT_SECRET=' /var/iri/code/env.d/frontend.env` prints
  `1` (counts, never prints the value).
- [ ] Keycloak → `basetool-frontend` → Settings → *Client authentication* `On`; *PKCE Method*
  still `S256`.
- [ ] The latest `CODE_TO_TOKEN` event shows client-secret authentication.
- [ ] A private-window login completes.
- [ ] No `CODE_TO_TOKEN_ERROR` / `LOGIN_ERROR` with `invalid_client` in the last 15 minutes.
- [ ] `backend-service` (the client behind `KEYCLOAK_ADMIN_CLIENT_SECRET`) is untouched and still
  works — only `basetool-frontend` changed.
- [ ] Existing sessions were not invalidated.

## Risks & caveats

- **Another secret to guard.** It lives in `/var/iri/code/.env` (`deploy:deploy 0640`, gitignored,
  excluded from the config bundle) and in the rendered `env.d/frontend.env` (`0640`), and it rides
  in every backup's `config/dotenv`. It joins the rotation list in
  [`docs/backup.md`](backup.md#rotate-secrets-after-a-compromise-driven-restore).
- **Test contexts.** Every `@SpringBootTest` without the placeholder from A.4 fails at context
  start — CI catches a forgotten A.4 immediately.
- **Local and E2E realms** (A.5) must switch together with the code, or local and E2E logins fail.

---

## Prompt for an AI agent

Self-contained; hand it to a new session with repository access.

```
Implement audit finding M-6 (ADR-0001) in the basetool repository: switch the frontend's OAuth2
client registration with Keycloak from public (PKCE only) to confidential (client secret + PKCE).

Do Part A of docs/OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md exactly (A.1-A.7), including the
regenerated Quadlet template and the local/E2E realm changes. Do NOT touch any production realm,
the production .env or any production host. Test placeholders must be obviously synthetic
(`test-client-secret`), never production-like.

Verify with ./gradlew spotlessApply, the frontend lint tasks and ./gradlew :frontend:test, then
./gradlew check. Return a summary of the code changes; Parts B and C are run by the owner from the
runbook itself.
```

---

## Related documents

- [ADR-0001](adr/0001-frontend-confidential-oauth2-client.md) — the decision.
- [`KEYCLOAK_HARDENING_RUNBOOK.md`](KEYCLOAK_HARDENING_RUNBOOK.md) — step 6 (PKCE `S256`, the
  interim state) and why it does not close M-6.
- [`docs/archive/MULTI_SQUADRON_PLAN.md`](archive/MULTI_SQUADRON_PLAN.md) — referenced for pattern
  conventions.
- [`README.md`](../README.md) — env vars and the local test stack.
- [`CHANGELOG.md`](../CHANGELOG.md) — audit findings 2026-05-20, `### Security` block.
- [`frontend/src/main/resources/application.yml`](../frontend/src/main/resources/application.yml) —
  the starting configuration.
