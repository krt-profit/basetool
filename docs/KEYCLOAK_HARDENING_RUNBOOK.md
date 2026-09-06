> **Doc type:** Operator runbook — **the owner runs every step; nothing here is automated and
> nothing here ships with the image.** Written 2026-09-06 as WP-K2 of
> [`MEMBERS_ONLY_PLAN.md`](MEMBERS_ONLY_PLAN.md); the owner took all twelve items on 2026-09-05
> (decision D11), the two originally marked optional included.
> **Requirement:** [REQ-SEC-052, REQ-SEC-053](specs/security-and-access.md) ·
> **ADR:** [0159](adr/0159-the-basetool-has-no-anonymous-or-guest-surface.md)

# Keycloak hardening runbook (WP-K2)

Twelve changes to the production realm, each independent of the code and of each other. Run them
**after** the members-only release is promoted, **one at a time**, in the Admin Console or through
`kcadm` under the production account.

> [!important] This is the owner's list, not Claude's
> Every step below is a **write** against the production Keycloak. The repository's production-host
> rule forbids an agent from running any of them, with or without approval, and there is no
> emergency exception. What this document is for is the opposite: each step states the exact change,
> what it breaks if it is wrong, and the one line that undoes it — so the person running it does not
> have to re-derive any of that at the console.

> [!warning] Verify the realm's roles read-only first
> Before step 1, confirm what the realm actually holds:
> `kcadm get roles -r iri --fields name`. If a `Guest` role exists there it is a leftover — the
> sanitized production reference does not carry one — and deleting it is a thirteenth step. The
> application no longer maps it either way (`V239`), so this is tidiness, not a dependency.

---

## The twelve steps

| # | Change | Why | Rollback |
|---|--------|-----|----------|
| 1 | `editUsernameAllowed: true` → `false` | A username is the identity the app's roster, the audit log and the approval queue are read by. Letting a member change it silently re-labels their history. | Set it back to `true`. No data is touched either way. |
| 2 | `resetPasswordAllowed` — **decide on Keycloak's own SMTP**, not the backend's | The realm's `smtpServer` block *is* configured; the backend's mail is off. Those are different senders, and the decision has been taken on the wrong one before. Send a test mail from the realm before enabling. | Set it back. A member who started a reset simply cannot finish it. |
| 3 | `sslRequired: none` → `external` | The realm currently accepts a plaintext token exchange from any address. `external` requires TLS for everything but loopback, which is what the deployment already does. | `none`. Do this one first if anything else that touches the realm goes wrong — a wrong value here locks the console out over a non-TLS hop. |
| 4 | `eventsEnabled` and `adminEventsEnabled` → on, `eventsExpiration` 30 d, the admin-events expiration set alike. **Do not** enable `adminEventsDetailsEnabled`. | Today there is no login-failure, token-error or client-disable event anywhere — the "detect" half of the security ladder is blind on the token endpoint. The details flag is left off deliberately: it records request bodies, which is the one place a credential could land in the event store. | Switch both off. The stored events remain until they expire. |
| 5 | Clear the `/*` `redirectUris` and `webOrigins` on `backend-service` and `basetool-ingest-gateway` | Both are service-account-only clients: they never perform a browser redirect, so a wildcard there is a standing offer nobody needs. | Re-add `/*`. Neither client uses the field, so nothing observable changes in either direction — which is exactly why it was never noticed. |
| 6 | `basetool-frontend`: set `pkce.code.challenge.method=S256` | It is a public client without PKCE. An intercepted authorization code is redeemable without it. | Clear the attribute. **Test the web login immediately after**: a client that advertises PKCE while the adapter does not send a verifier fails at the token exchange, not at the redirect, so the symptom is a login that gets all the way back and then errors. |
| 7 | `basetool-frontend`: drop the stale `http://backend:11261` redirect URI and web origin | An internal Docker hostname on a browser client's redirect list. It cannot be reached from a browser, so it grants nothing today; it is a leftover that would become a real redirect target the day that name resolves. | Re-add both strings verbatim. |
| 8 | `basetool-sc-extractor`: `fullScopeAllowed: true` → `false`, with an explicit role scope | The extractor is a public client on members' desktops. With full scope its tokens carry every realm role the holder has, including `Admin`. Model it on `basetool-android`, which already runs narrowed. | Set `fullScopeAllowed` back to `true`. **Check the extractor still ingests** before considering the step done: a scope that is too narrow fails at the ingest gateway's audience check, not at login. |
| 9 | Remove `extractor-ingest` and `extractor-ingest-only` from the realm's **default** client scopes; assign each only where it is needed — `basetool-frontend` and `basetool-sc-extractor` keep `extractor-ingest`, only the extractor gets `extractor-ingest-only`, and `grafana` loses `extractor-ingest` | These two scopes stamp the backend and ingest audiences onto **every** client in the realm, including `grafana`. An audience claim is what a resource server trusts; handing it to every client makes the audience check decorative. | Re-add both to the realm defaults. Verify one token per affected client after the change — a missing audience is refused by the resource server with a `401` that reads like an expired token. |
| 10 | Drop `offline_access` from `default-roles-iri` | Every account can currently mint an offline token, which outlives every session policy in the realm. | Re-add the role to the composite. **Check the Android app's refresh first**: if it relies on an offline token rather than a refresh token, this step signs every installation out. |
| 11 | Require OTP for holders of `Admin`: a conditional subflow (role condition → OTP `REQUIRED`) in the **browser** flow **and** bound as `postBrokerLoginFlowAlias` on the `discord` IdP | The browser flow alone is half the gate: an admin who signs in through Discord never traverses it. This is the item most likely to be done incompletely, and the incomplete version looks finished. | Set the subflow's requirement to `DISABLED` and unbind the post-broker flow. Keep a second admin session open while doing this — an OTP flow that is wrong locks the console. |
| 12 | Review `rememberMe` and the 30 d / 180 d SSO windows against the session policy the tool assumes | Not a defect, a decision that has never been made explicitly. The frontend's own session is 720 h; the realm's windows are what actually decide how long a stolen browser stays useful. | Restore the previous values, which the export in `docs/keycloak/realm-config.reference.json` records. |

---

## After the twelve

Re-export the sanitized realm and commit it:

```bash
python scripts/sanitize-realm-export.py <export.json> docs/keycloak/realm-config.reference.json
```

The sanitizer keeps `authenticationFlows`, `authenticatorConfig` and `requiredActions` since WP-K1,
so step 11's flow is version-controlled rather than living only in the console — which is the whole
reason that change was made to the sanitizer.

Then close the open finding in [`docs/keycloak/README.md`](keycloak/README.md), which records
`fullScopeAllowed: true` on the frontend and the extractor. Step 8 closes the extractor half. The
frontend half is **not** in this list: it is a public browser client whose scope is the member's own
roles, and narrowing it is ADR-0001's confidential-client migration rather than a hardening step.

> [!note] Nothing here is required for the members-only release to be correct
> The release stands on its own — REQ-SEC-052 and REQ-SEC-053 are enforced in the application, and
> the sweeps assert them. These twelve reduce the blast radius *around* it: what an intercepted code
> is worth, what a token carries, and whether anyone can see it happen.
