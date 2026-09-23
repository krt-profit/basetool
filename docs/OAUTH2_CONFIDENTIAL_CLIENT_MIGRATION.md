# OAuth2 confidential-client migration (audit finding M-6)

> **Doc type:** Implementation runbook for [ADR-0001](adr/0001-frontend-confidential-oauth2-client.md)
> and REQ-SEC-069. The *decision* and its rationale live in the ADR; this document is the
> step-by-step *how*. Registered in [`docs/specs/INDEX.md`](specs/INDEX.md). Rewritten 2026-09-23 when
> the code part shipped (improvement audit finding APPSEC-07).

**Status:** the **code part is done** and inert; the **production rollout is open** and needs the
owner's yes for each write below. Until then production's `basetool-frontend` is a public client
(PKCE `S256`, hardening step 6) and the frontend runs without `KEYCLOAK_FRONTEND_CLIENT_SECRET`.
**Audit findings:** M-6 (security audit 2026-05-20), APPSEC-07 (improvement audit 2026-09-22).

> [!warning] Corrected 2026-09-23 — the migration is no longer a maintenance window
> Earlier revisions of this runbook said logins fail between flipping the Keycloak client and
> deploying the secret, and planned 5–10 minutes of downtime. That was true of the design it
> described (Keycloak first, then the frontend). Two facts remove the window: Keycloak accepts a
> client secret from a client it still considers public (it ignores it — measured on Keycloak 26.7,
> 2026-09-23), and the frontend now derives its client type from whether the secret is set. So the
> frontend goes first, Keycloak second, and there is no moment in which either side refuses the
> other. The old Parts A–C are replaced by the sequence below.

## What the secret adds

With PKCE alone, whoever captures the authorization code **and** the PKCE verifier can redeem it;
the code travels as `?code=…` on `/login/oauth2/code/keycloak`, through the TLS-terminating edge.
With a client secret as well, a captured code cannot be redeemed without the secret, which only the
frontend's environment holds. PKCE stays on: the pattern is **PKCE + client secret**.

## What shipped (code, REQ-SEC-069)

- **One variable decides.** `KEYCLOAK_FRONTEND_CLIENT_SECRET` set → the `keycloak` registration is
  `client_secret_basic` + PKCE; unset or blank → `none` + PKCE, exactly as before
  (`FrontendClientAuthenticationConfig`). The method and the secret cannot disagree.
- **PKCE in both modes.** Spring Security 7's `ClientSettings.requireProofKey` defaults to `true`;
  `FrontendClientAuthenticationConfigTest` pins it, because Keycloak requires `S256` and a
  confidential client that stopped sending PKCE would fail every login.
- **Existing sessions follow the switch.** `CurrentRegistrationAuthorizedClientRepository` stores
  the authorized client **without** the secret and gives it the **current** registration on every
  read. A session created while the frontend was public therefore refreshes as the confidential
  client at once — nobody is signed out — and the secret never lands in Redis.
- **The provisioner changes the type only when told.** `provision-keycloak-realm.py` leaves the
  frontend client's type as it is unless `--frontend-client public|confidential` is passed; with
  `confidential` it sets Keycloak's secret from `$KEYCLOAK_FRONTEND_CLIENT_SECRET` (stdin to kcadm,
  never printed) in the same update that flips the client, and refuses without the variable
  (ADR-0202 amendment 2).
- **E2E proves it.** The E2E realm's `basetool-frontend` is confidential with a throwaway secret, the
  E2E frontend receives it, and every Playwright login goes through the confidential client.
- `compose` passes `KEYCLOAK_FRONTEND_CLIENT_SECRET` with an **empty default** (`:-`, not `:?`), so a
  release carrying this renders and starts on a host whose `.env` lacks it.

## Rollout (production — every step is a write that needs the owner's yes)

As root, from `/`, with the [shell conventions](deployment.md#shell-conventions-used-below)
(`UCTL`, `UPOD`). The release carrying the code must be deployed first.

**1. Generate the secret on the host and give it to the frontend** (Keycloak still public):

```bash
cd /
cp -p /var/iri/code/.env /var/iri/code/.env.backup-$(date +%Y%m%d-%H%M%S)
grep -q '^KEYCLOAK_FRONTEND_CLIENT_SECRET=' /var/iri/code/.env || \
  printf 'KEYCLOAK_FRONTEND_CLIENT_SECRET=%s\n' "$(openssl rand -base64 48 | tr -d '/+=\n')" >> /var/iri/code/.env
sudo -u deploy /var/iri/code/scripts/render-env-d.py \
  --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
grep -c '^KEYCLOAK_FRONTEND_CLIENT_SECRET=.' /var/iri/code/env.d/frontend.env    # 1 (counts, never prints)
${UCTL} restart frontend.service
${UPOD} logs --since 5m frontend 2>&1 | grep "OAuth2 client 'keycloak' is"       # ... is CONFIDENTIAL
```

The frontend now sends the secret; Keycloak, still public, ignores it. Log in once in a private
window — it must work exactly as before. Nobody is signed out.

**2. Switch Keycloak, with the same secret, in one update** (the provisioner session of
[`INGEST_KEYCLOAK_SETUP.md` → *New or out-of-date realm*](INGEST_KEYCLOAK_SETUP.md#new-or-out-of-date-realm-run-the-provisioner),
steps 1–3, for `kc`/`KCADM` and the rollback basis):

```bash
S="$(sed -n 's/^KEYCLOAK_FRONTEND_CLIENT_SECRET=//p' /var/iri/code/.env | tail -1)"
KEYCLOAK_FRONTEND_CLIENT_SECRET="$S" python3 /root/kc-realm/provision-keycloak-realm.py --realm iri \
  --public-origin https://profit-base.online --kcadm-command "$KCADM" --frontend-client confidential
#   dry run: expect "~ publicClient: true -> false", "~ clientAuthenticatorType: …" and
#   "~ secret: set from $KEYCLOAK_FRONTEND_CLIENT_SECRET (the value is not printed)"
KEYCLOAK_FRONTEND_CLIENT_SECRET="$S" python3 /root/kc-realm/provision-keycloak-realm.py --realm iri \
  --public-origin https://profit-base.online --kcadm-command "$KCADM" --frontend-client confidential --apply
unset S
```

A dry run that plans anything beyond the frontend's client type is a realm that has drifted — stop
and read it before applying. Keycloak now demands the secret, and the frontend already sends it.

**3. Verify:**

- A private-window login completes; an open tab of an existing session keeps working across its
  next token refresh (≤ 5 minutes).
- Keycloak → *Events* → the latest `CODE_TO_TOKEN` for `basetool-frontend` names the client-secret
  authenticator; no `invalid_client` / `CODE_TO_TOKEN_ERROR` in the last 15 minutes.
- `FrontendLoginBroken` and `KeycloakLoginErrorSpike` stay silent — a secret mismatch is exactly what
  they fire on (`invalid_client` at the token endpoint is `reason="provider_error"` on
  `basetool_login_total`).
- Keycloak → `basetool-frontend` → *Advanced*: *PKCE Method* is still `S256`.

**Rollback**, from either step:

- After step 2: `python3 … --frontend-client public --apply` (no secret is sent), then keep going
  with the step-1 rollback if wanted. Keycloak ignores the secret the frontend still sends.
- After step 1: delete the `KEYCLOAK_FRONTEND_CLIENT_SECRET` line (or leave it), render `env.d`,
  `${UCTL} restart frontend.service` — the frontend is the public client again.

**Rotation** cannot simply repeat step 1: a confidential client refuses the old secret the moment
Keycloak holds the new one, and the frontend cannot switch at that same instant. So rotate through
the public state, which accepts both: `--frontend-client public --apply`, then a new value into
`.env` (replace the line) with the rest of step 1, then step 2 again. Existing sessions survive every
step, because they refresh with the registration the frontend runs with now.

**After the rollout**, make `--frontend-client confidential` part of every documented provisioner
run, and update the host's `/var/iri/code/realm-export.json` seed (`"publicClient": false`,
`"clientAuthenticatorType": "client-secret"`, no secret) so a realm rebuilt from it is not public.

## Risks & caveats

- **Another secret to guard.** It lives in `/var/iri/code/.env` and the rendered
  `env.d/frontend.env` (`0640`), rides in every backup's `config/dotenv`, and joins the rotation list
  in [`docs/backup.md`](backup.md#rotate-secrets-after-a-compromise-driven-restore). It does **not**
  reach Redis: the stored authorized client carries an empty secret.
- **Local stacks.** The dev and test stacks keep a public client unless their `.env.test` sets the
  variable; the E2E stack is confidential by design.

## Related documents

- [ADR-0001](adr/0001-frontend-confidential-oauth2-client.md) — the decision.
- [ADR-0202](adr/0202-a-realm-is-brought-to-the-production-shape-by-a-provisioner-that-never-deletes.md) — the provisioner, amendment 2.
- [`KEYCLOAK_HARDENING_RUNBOOK.md`](KEYCLOAK_HARDENING_RUNBOOK.md) — step 6 (PKCE `S256`).
- [`CHANGELOG.md`](../CHANGELOG.md)
