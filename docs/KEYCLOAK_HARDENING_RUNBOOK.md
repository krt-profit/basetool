> **Doc type:** Operator runbook — **the owner runs every step; nothing here is automated and
> nothing here ships with the image.** Written 2026-09-06 as WP-K2 of the (now archived)
> [`MEMBERS_ONLY_PLAN.md`](archive/MEMBERS_ONLY_PLAN.md); the owner took all twelve items on 2026-09-05
> (decision D11), the two originally marked optional included. Rewritten 2026-09-08 from a decision
> list into an executable procedure, against the Keycloak **26.7** sources the deployment pins.
> **Living document** — the per-step status below is kept current. Last reviewed: 2026-09-22
> (status re-checked against the realm reference; commands rewritten for the rootless-Podman
> production host). **Requirement:** [REQ-SEC-052, REQ-SEC-053](specs/security-and-access.md) ·
> **ADR:** [0159](adr/0159-the-basetool-has-no-anonymous-or-guest-surface.md)

# Keycloak hardening runbook (WP-K2)

Twelve changes to the production realm `iri`. Each states the exact change, the Admin Console path,
the equivalent `kcadm` command, **how to verify it took**, the one line that undoes it, and what
breaks if it is wrong.

> [!important] This is the owner's procedure
> Every step below is a **write** against the production Keycloak. The owner runs them. Under the
> repository's production-host rule (`CLAUDE.md`, since 2026-09-12) an agent may *read* the realm,
> but may run a write only after @greluc has approved that exact command in chat — per command, with
> no emergency exception. This document exists so the person running them does not have to
> re-derive anything at the console.

## Status

Nine of the twelve are applied; three are open, plus a thirteenth finding (decided 2026-09-22,
applied to production by `scripts/provision-keycloak-realm.py` on 2026-09-23 with the owner's yes;
the testing realm is not provisioned yet) and the clean-up. The
evidence is the sanitized realm export regenerated from production on **2026-09-09**
([`docs/keycloak/realm-config.reference.json`](keycloak/realm-config.reference.json), commit
`72b9b1b2b`, which recorded "steps 1 and 3–10 applied, 11 not, 2 and 12 open"), and for step 4 a
read-only query of `db-keycloak` on 2026-09-16. Production may have moved since: **re-read the live
value (§ 0.5) before acting on an "open" row.**

| Step  |                    Change                     |             Status             |                                            Evidence (2026-09-09 export unless noted)                                             |
|-------|-----------------------------------------------|--------------------------------|------------------------------------------------------------------------------------------------------------------------------------|
| 3     | `Require SSL` → `external`                    | **Done**                       | `sslRequired: "external"`                                                                                                        |
| 1     | `Edit username` off                           | **Done**                       | `editUsernameAllowed: false`                                                                                                     |
| 2     | `Forgot password` — decide on Keycloak's SMTP | **Open**                       | `resetPasswordAllowed: true`; no record that the Email tab's *Test connection* was run                                           |
| 4     | Events on, 30 d                               | **Done** — verified 2026-09-16 | both event switches on, `eventsExpiration` and the `adminEventsExpiration` attribute `2592000`, details off                      |
| 5     | Clear service-account redirect/origin lists   | **Done**                       | `backend-service`, `basetool-ingest-gateway`: `redirectUris: []`, `webOrigins: []`                                               |
| 6     | `basetool-frontend` PKCE `S256`               | **Done**                       | `pkce.code.challenge.method: S256`                                                                                               |
| 7     | Drop `http://backend:11261`                   | **Done**                       | gone from both lists; `http://frontend:18081` remains — **retired 2026-09-22** by owner decision, removed on the provisioner's next production apply |
| 8     | Extractor `fullScopeAllowed: false`           | **Done**                       | `basetool-sc-extractor`: `fullScopeAllowed: false`                                                                               |
| 9     | Audience scopes off defaults and `grafana`    | **Done**                       | neither scope in `defaultDefaultClientScopes`; `grafana` carries neither; `basetool-ingest-gateway` keeps both                   |
| 10    | `offline_access` off `default-roles-iri`      | **Done**                       | composites: `uma_authorization`, `KRT Member`, `account` `view-profile`/`manage-account`                                         |
| 12    | Decide the session windows                    | **Open**                       | still 30 d / 180 d, with and without remember-me                                                                                 |
| 11    | OTP for `Admin`, browser **and** Discord      | **Open** — not started         | `browserFlow: "browser"` (the built-in flow); the `discord` IdP has no `postBrokerLoginFlowAlias`                                 |
| 13th  | Extractor's unused authorization-code flow    | **Decided 2026-09-22 — off**; pending production apply | `standardFlowEnabled: true`, loopback redirect wildcards, no PKCE (see step 6); the provisioner's target is flow off, no redirect URI |
| After | Delete `basetool-provisioner`, re-export      | **Open**                       | the client is in the export (taken mid-procedure); it stays until step 11 is done                                                |

**Another realm does not need these steps replayed for the client-side half.** Since 2026-09-22
`scripts/provision-keycloak-realm.py` reproduces production's state for steps 5–9 — the cleared
service-account lists, the frontend's PKCE `S256` and redirect list, the extractor's
`fullScopeAllowed: false`, and the audience scopes only where production has them — along with the
rest of the Basetool's clients
([`INGEST_KEYCLOAK_SETUP.md` → *New or out-of-date realm*](INGEST_KEYCLOAK_SETUP.md#new-or-out-of-date-realm-run-the-provisioner)).
The realm-wide steps (1–4, 10–12) are **not** in it, and neither is 9a: it reports an audience scope
that is a realm default instead of removing it.

**The thirteenth finding is decided (owner, 2026-09-22): the extractor's code flow goes.** So do two
other entries the provisioner first reproduced as `PROD-AS-IS` — both ingest scopes on
`basetool-android`, and step 7's leftover `http://frontend:18081` pair (ADR-0202 amendment 1).
The
script's target shape no longer has any of them, and it removes them wherever it finds them, so
**applying it to production closes all three**: a dry run there lists exactly those removals
(plus the DPoP policy's detach and re-attach around the app's scopes). Until that apply runs,
production still carries them.

---

## 0. Before you start

### 0.1 What this was written against

Production pins `quay.io/keycloak/keycloak:26.7` by digest (26.7.4 by its label since 2026-09-16) —
declared in `docker-compose.yml` and carried into the generated Quadlet unit
`quadlet/systemd/keycloak.container`. Every field name, menu label and endpoint below was read out
of the **26.7.0 sources**, not from memory:

|                                                                                              Claim                                                                                              |                                                                                                                         Source                                                                                                                          |
|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Console labels (*Edit username*, *Forgot password*, *Require SSL*, *Save events*, *Include representation*, *Expiration*, *Full scope allowed*, *Default roles*, *Require PKCE*, *PKCE Method*) | `js/apps/admin-ui/…/messages_en.properties` @ 26.7.0                                                                                                                                                                                                    |
| `Require SSL` modes                                                                                                                                                                             | [Server Admin Guide § Configuring SSL for a realm](https://www.keycloak.org/docs/26.7.0/server_admin/#_ssl_modes)                                                                                                                                       |
| Event settings and `kcadm` event syntax                                                                                                                                                         | Server Admin Guide § *Auditing user events* / *Auditing admin events* / *Configuring event logging for a realm*                                                                                                                                         |
| `Condition - User Role` fields                                                                                                                                                                  | [§ Conditions in conditional flows](https://www.keycloak.org/docs/26.7.0/server_admin/#conditions-in-conditional-flows)                                                                                                                                 |
| `Post login flow`                                                                                                                                                                               | [§ Post login flow](https://www.keycloak.org/docs/26.7.0/server_admin/#_identity_broker_post_login_flow)                                                                                                                                                |
| Realm **default** client scopes apply to *newly created* clients only                                                                                                                           | [§ Realm default client scopes](https://www.keycloak.org/docs/26.7.0/server_admin/#_client_scopes_linking)                                                                                                                                              |
| `Full scope allowed` lives on the `<client>-dedicated` scope's *Scope* tab                                                                                                                      | [§ Dedicated client scope](https://www.keycloak.org/docs/26.7.0/server_admin/#_client_scopes_dedicated)                                                                                                                                                 |
| Client-scope and events endpoints                                                                                                                                                               | `RealmAdminResource.java`, `ClientResource.java`, `RealmEventsConfigRepresentation.java` @ 26.7.0                                                                                                                                                       |
| `kcadm` command syntax                                                                                                                                                                          | [§ Admin CLI](https://www.keycloak.org/docs/26.7.0/server_admin/#admin-cli)                                                                                                                                                                             |
| Creating the provisioning client (§ 0.3): the wizard, the service-account switch, the Credentials tab                                                                                           | § *Creating an OpenID Connect client* · [§ Using a service account](https://www.keycloak.org/docs/26.7.0/server_admin/#_service_accounts) · [§ Confidential client credentials](https://www.keycloak.org/docs/26.7.0/server_admin/#_client-credentials) |
| A client the wizard creates carries `fullScopeAllowed = true`                                                                                                                                   | `RepresentationToModel#defaultFullScopeAllowed` @ 26.7.0 (`isNew && !consentRequired`)                                                                                                                                                                  |

If the realm is upgraded past 26.7, re-check the three items marked **⚠ version-sensitive** below
before running them.

### 0.2 Three things the previous version of this runbook got wrong

Found while writing the commands out. Each would have looked like a completed step.

1. **`adminEventsExpiration` is not part of the events config.** ⚠ version-sensitive
   `RealmEventsConfigRepresentation` @ 26.7.0 carries `eventsEnabled`, `eventsExpiration`,
   `eventsListeners`, `enabledEventTypes`, `adminEventsEnabled`, `adminEventsDetailsEnabled` — and
   **no** admin-events expiration. That value is a **realm attribute**
   (`RealmAttributes.ADMIN_EVENTS_EXPIRATION = "adminEventsExpiration"`), written through
   `realms/iri`. `kcadm update events/config -s adminEventsExpiration=…` is accepted and does
   nothing. Step 4 now sets it in the right place.

2. **Removing a scope from the realm defaults changes nothing for existing clients.** The guide is
   explicit: realm default client scopes "define sets of client scopes that are automatically linked
   to **newly created clients**". `grafana`, `basetool-frontend`, `basetool-ingest-gateway` and
   `basetool-sc-extractor` already carry `extractor-ingest`; clearing the realm default leaves every
   one of them exactly as it is. Step 9 is now two halves, and the second is the one that matters.

3. **`eventsExpiration` is seconds over the API and a picker in the console.** The console renders
   *Expiration* with `units={["minute","hour","day"]}`; the REST field is a time-to-live **in
   seconds**. 30 days is `2592000`. A bare `30` means thirty seconds.

### 0.3 The identity you run this as — create it first

**Not your admin account.** `kcadm config credentials` offers `--user/--password`,
`--client/--secret` and `--client/--keystore`, and none of them can carry a second factor — so after
step 11 an admin account with OTP **cannot authenticate to kcadm at all**, and the failure reads
`invalid_grant` / *"Invalid user credentials"*, which looks like a wrong password. The realm is
`bruteForceProtected` with `failureFactor: 5`, so retrying walks the account into a lockout instead.

Use a **short-lived provisioning client with a service account**, created for this procedure and
deleted at the end of it (§ *After the twelve*, step 1). Four steps in the Admin Console — labels
as 26.7 writes them:

1. ***Clients*** → **Create client**. Leave *Client type* on **OpenID Connect**, set *Client ID* to
   `basetool-provisioner`, **Save**.
2. On the *Settings* tab, under **Capability config**: set **Client authentication** to **On** (this
   is what makes it confidential), and under **Authentication flow** leave **only** *Service account
   roles* ticked — clear **Standard flow**, **Direct access grants**, **Implicit flow** and
   **OAuth 2.0 Device Authorization Grant**. It is not a login client and must not be able to act as
   one. **Save**.
3. ***Credentials*** tab. *Client Authenticator* stays on **Client ID and Secret** (the default; a
   random secret is generated for you). Copy the **Client Secret** — that is the value
   `kcadm config credentials` prompts for in § 0.4.
4. ***Service account roles*** tab → **Assign role** → **Filter by clients** → `realm-management`
   → tick the **four** roles below → **Assign**.

**Which roles, and which step demands each.** The Admin API checks a different permission per
resource, so "an admin role" is not a thing you can assign here — each row was read off the endpoint
the step calls, at 26.7.0:

|              Role               |                   Needed by                   |                                                                                 The check                                                                                  |
|---------------------------------|-----------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `manage-realm`                  | steps 1, 2, 3, 4 (the attribute), 10, 11a, 12 | `RealmAdminResource#updateRealm` → `requireManageRealm`; `AuthenticationManagementResource` → `requireManageRealm`; `RoleContainerResource` → `auth.roles().requireManage` |
| `manage-clients`                | steps 5, 6, 7, 8, 9                           | `ClientResource` → `auth.clients().requireManage`; `RealmAdminResource#removeDefaultDefaultClientScope` → `auth.clients().requireManageClientScopes`                       |
| **`manage-events`**             | **step 4**                                    | `RealmAdminResource#updateRealmEventsConfig` → `requireManageEvents` — **not** `manage-realm`, which is why step 4 answers `403` without it                                |
| **`manage-identity-providers`** | **step 11b**                                  | `IdentityProviderResource#update` → `requireManageIdentityProviders` — binding the post-login flow onto `discord` is an identity-provider write, not a realm write         |

Add **`view-events`** as well if you want § 0.5's `get events/config` capture to succeed: the read is
gated on `requireViewEvents` separately from the write.

**And no more than those.** Editing its own role mappings would need `manage-users`, which it
deliberately does not get — so the identity cannot widen its own reach. `realm-admin` would cover
everything in one tick and is exactly what this list exists to avoid: it also carries `manage-users`,
which turns a procedure credential into one that can mint and elevate accounts.

> [!bug] This list was two roles until 2026-09-09, and step 4 was the first to fail
> The earlier version carried `manage-clients` + `manage-realm`, inherited from
> `docs/keycloak/README.md`, where they were verified for a **different** procedure — the mobile
> client, which touches clients and client policies and nothing else. This runbook goes further:
> events config and the identity provider are separate permissions in Keycloak's Admin API, and
> neither is implied by `manage-realm`. Reported from the production console on step 4
> (`HTTP 403 Forbidden` on `update events/config`).
>
> [!warning] Do **not** turn *Full scope allowed* off on this client
> Keycloak's own service-account procedure ends by pointing at the dedicated client scope's *Scope*
> tab and recommending that switch be **off** in production. That advice is for long-lived clients
> and is wrong here: a client the wizard creates carries `fullScopeAllowed = true`
> (`RepresentationToModel#defaultFullScopeAllowed`: `isNew && !consentRequired`), which is exactly
> what lets the two roles above reach the token without any scope work. Turning it off without
> adding matching role scope mappings silently empties the token, and the symptom is a **403
> half-way through the procedure** — on a step that looked fine a minute earlier. This client is
> deleted at the end; that is what bounds it, not the scope switch.
>
> [!warning] The role assignment is verified in § 0.4, not here
> `manage-realm` is what the authentication-flow endpoints check, and a missing assignment is worth
> catching before step 11 rather than half-way through building a flow. But every check is a kcadm
> read, and kcadm has no session until § 0.4 opens one — so the verification lives there, at the end
> of that section. Finish creating the client here; verify it there.

The same identity, its two roles and the reasoning are also in
[`docs/keycloak/README.md`](keycloak/README.md), where it was written for the mobile-client
provisioning runbook. It is repeated here rather than linked because a runbook that sends you to
another document for the credential you cannot start without is not a runbook.

### 0.4 Open a session

Production Keycloak serves **HTTPS only, on 18443** (`--http-enabled=false`) with the shared
self-signed keystore, so kcadm needs a truststore first. Both facts were verified against a 26.7
container started with the production command line (2026-08-17); `KC_HOSTNAME_STRICT=true` does not
interfere.

**On the production host (rootless Podman, since 2026-09-22)** the container belongs to the service
user `iri`, so every call goes through `sudo -u iri podman exec`, and the Keycloak container runs
with a **read-only root filesystem** (`ReadOnly=true` in `quadlet/systemd/keycloak.container`).
kcadm's default session file, `/opt/keycloak/.keycloak/kcadm.config`, cannot be written there; the
only writable paths are the unit's tmpfs mounts. The session therefore goes to
`/opt/keycloak/data/tmp/kcadm.config` via kcadm's `--config` option, on **every** call. Define the
helper once per shell — as root, **from `/`** (`sudo -u` keeps the working directory, and `iri`
cannot enter `/root`; see [`deployment.md` → *Shell conventions*](deployment.md#shell-conventions-used-below)),
with the § 0.5 captures kept in a root-only directory:

```bash
cd /
KCDIR=/root/kc-hardening; install -d -m 0700 "$KCDIR"
KCCFG=/opt/keycloak/data/tmp/kcadm.config
kc() { sudo -u iri podman exec -i keycloak /opt/keycloak/bin/kcadm.sh "$@" --config "$KCCFG"; }

sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config truststore \
    --trustpass - /run/secrets/keystore.p12 --config "$KCCFG"

sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
    --server https://localhost:18443/auth --realm iri --client basetool-provisioner --config "$KCCFG"
```

Every `kc …` line below is that helper. The read-only root and the `--config` workaround were
derived from the generated unit on 2026-09-22 and first exercised on the production host on
2026-09-25: if `config truststore` answers with a read-only-filesystem error, the `--config` is
missing. **Since the internal-TLS step-3 release (#2036, v1.12.0 — on production since
2026-09-25)**, `/run/secrets/keystore.p12` is Keycloak's own leaf keystore and the unit mounts no
truststore; the `config truststore` line above is **untested** in that shape — see the open question in
[`keycloak/README.md` → *Runbook — provisioning the mobile client*](keycloak/README.md#runbook--provisioning-the-mobile-client-basetool-android).
On a Docker Compose host (the local stack) the root filesystem is writable, and
`kc …` without `--config` works as before.

> [!note] The `/auth` in `--server` is load-bearing since 2026-09-13
> Keycloak serves under that relative path ([ADR-0166](adr/0166-identity-moves-onto-the-app-origin.md)),
> so a base URL without it answers 404 — and kcadm surfaces that as an authentication problem rather
> than as a wrong address, which is a long detour to take.
>
> [!important] `No server specified. Use --server, or 'kcadm.sh config credentials'.`
> That message does **not** mean a flag is missing. It is what kcadm says for **every** command
> when no session exists — including reads that carry no `--server` because they are not supposed
> to. Seeing it means the two commands above have not run, the call went without `--config`, or the
> container **restarted**: the session file lives on a tmpfs and does not survive a restart, let
> alone a recreate. Run them again, then repeat whatever you were doing.
>
> It is the first thing a reader of this runbook hits if they try any `kcadm` line before this
> section, and it sends them looking for a URL problem they do not have.

Then a **read-only smoke test**, so a URL, truststore or permission problem surfaces on a command
that changes nothing:

```bash
kc get realms/iri \
    --fields realm,sslRequired,editUsernameAllowed,resetPasswordAllowed
```

**Then verify every role actually took** — this is the check § 0.3 defers to, and it is one read per
role. The realm read above proves only `view-realm`; each of the four is gated separately, so a
missing one shows up on its own step and nowhere earlier:

```bash
kc get authentication/flows -r iri --fields alias
kc get clients -r iri --fields clientId
kc get events/config -r iri
kc get identity-provider/instances -r iri --fields alias
```

All four must return data. A **403** names the missing role — in order: `manage-realm`,
`manage-clients`, `view-events` (and with it `manage-events`, which step 4 writes with), and
`view-identity-providers`/`manage-identity-providers`. Fix the assignment in § 0.3 **now**: the
alternative is discovering it as a failed write half-way through a step, which is where it was found
the first time.

> [!note] A read passing does not prove the matching write passes
> `view-events` and `manage-events` are different roles, as are the identity-provider pair. These
> four reads catch a role that was never assigned, which is the common failure; they cannot catch
> assigning only the `view-` half. Assign the four `manage-` roles from the table in § 0.3 and treat
> the reads as a smoke test, not as proof.

The service-account token carries the realm's 300 s access-token lifespan and the
client-credentials grant issues no refresh token, so a step run after a long pause may need
`config credentials` again. `kcadm.config` holds the truststore password and a token in cleartext
inside the container — `sudo -u iri podman exec keycloak rm -f "$KCCFG"` when done (a container
restart also discards it).

### 0.5 Capture the current state — this is the rollback basis

**Do not use `docs/keycloak/realm-config.reference.json` as the picture of production.** It was
regenerated on **2026-09-09**, mid-procedure, and it is sanitized: it records the intermediate state
the *Status* table cites and nothing later. It is a reference for *shape* and for what was true that
day, not for *current values*.

```bash
kc get realms/iri            > "$KCDIR/kc-realm.before.json"
kc get events/config -r iri  > "$KCDIR/kc-events.before.json"
kc get clients -r iri \
    --fields clientId,id,publicClient,serviceAccountsEnabled,fullScopeAllowed,redirectUris,webOrigins,defaultClientScopes,attributes \
                                                                          > "$KCDIR/kc-clients.before.json"
kc get default-default-client-scopes -r iri \
                                                                          > "$KCDIR/kc-default-scopes.before.json"
kc get roles/default-roles-iri/composites -r iri \
                                                                          > "$KCDIR/kc-default-roles.before.json"
```

**Every "Rollback" line below assumes these five files exist.** Steps 5, 7 and 9 restore *lists*,
and a list you did not write down first cannot be restored from this document.

Also confirm the leftover the plan flagged:

```bash
kc get roles -r iri --fields name
```

The sanitized reference carries no `Guest` realm role. If production has one it is a leftover; the
application stopped mapping it in `V239`, so deleting it is tidiness, not a dependency — and it is a
thirteenth step, not part of the twelve.

### 0.6 The order, and the two fixed points

Run them **one at a time**, verifying each before starting the next. Within that, two are not free
to move:

- **Step 3 (Require SSL) goes first.** It is protective, it is the one whose *failure* mode locks the
  console over a non-TLS hop, and doing it first means everything after it travels encrypted.
- **Step 11 (OTP for `Admin`) goes last.** It is the riskiest, and it is the one that takes your
  admin account away from kcadm (§ 0.3). Nothing after it would be runnable the same way.

The other ten are independent of each other and of the code. Steps 5–9 touch clients, so a login
test belongs after each of them rather than at the end.

---

## Step 3 — `Require SSL`: `none` → `external`

> **Status:** done — applied by 2026-09-09 (realm export of that day; see *Status* above).

**Run this one first.**

|           |                                                                                       |
|-----------|---------------------------------------------------------------------------------------|
| **Now**   | `sslRequired: "none"` — the realm accepts a plaintext token exchange from any address |
| **After** | `sslRequired: "external"` — TLS required except from loopback and private ranges      |

**Console:** *Realm settings* → *General* tab → **Require SSL** → **External requests** → *Save*.

**CLI:**

```bash
kc update realms/iri -s sslRequired=external
```

**Verify:**

```bash
kc get realms/iri --fields sslRequired
```

**Rollback:** `… update realms/iri -s sslRequired=none`

**If it goes wrong:** *External requests* permits `localhost`, `127.0.0.1`, `10.x`, `192.168.x`,
`172.16.x` and IPv6 link-local/unique-local without TLS — which is exactly the hop kcadm uses from
inside the container, so **this step does not cut off your own session**. `ALL` would; do not use it.
Everything member-facing already arrives over TLS from the edge.

---

## Step 1 — `Edit username`: on → off

> **Status:** done — applied by 2026-09-09 (realm export of that day; see *Status* above).

|           |                              |
|-----------|------------------------------|
| **Now**   | `editUsernameAllowed: true`  |
| **After** | `editUsernameAllowed: false` |

A username is the identity the roster, the audit log and the approval queue are read by. Letting a
member change it silently re-labels their own history.

**Console:** *Realm settings* → *Login* tab → **Edit username** → off.

**CLI:**

```bash
kc update realms/iri -s editUsernameAllowed=false
```

**Verify:** `… get realms/iri --fields editUsernameAllowed` → `false`.

**Rollback:** `… -s editUsernameAllowed=true`. No data is touched either way.

---

## Step 2 — `Forgot password`: decide on **Keycloak's own** SMTP

> **Status:** **open** — `resetPasswordAllowed` was still `true` on 2026-09-09 and the decision
> below has not been recorded. Record the outcome here when it is taken.

|         |                                                                                    |
|---------|------------------------------------------------------------------------------------|
| **Now** | `resetPasswordAllowed: true`, and the realm's `smtpServer` block **is** configured |

This is a decision, not a mechanical change, and it has been taken on the wrong sender before: the
**backend's** mail is off, the **realm's** is configured, and only the realm's is used for a
password reset.

**Test the sender before deciding:** *Realm settings* → *Email* tab → **Test connection**. The guide
notes that *Forgot password* requires `Host` and `From` on the Email tab to be set for Keycloak to
send the reset mail at all.

- Mail arrives → leave `Forgot password` **on**. Nothing to do.
- Mail does not arrive → turn it **off** until the sender works. A member who starts a reset they
  cannot finish is worse than a link that is not offered.

**Console:** *Realm settings* → *Login* tab → **Forgot password**.

**CLI:** `… update realms/iri -s resetPasswordAllowed=false` (or `true`).

**Verify:** `… get realms/iri --fields resetPasswordAllowed`.

---

## Step 4 — Turn on events ⚠ version-sensitive

|         |                                                                                                   |
|---------|---------------------------------------------------------------------------------------------------|
| **Was** | `eventsEnabled: false`, `adminEventsEnabled: false`, no expirations set                           |
| **Now** | both on, user events expiring after 30 d, admin events likewise, **`Include representation` off** |

> [!success] Done on production — verified 2026-09-16
> The realm reports `events_enabled = t`, `admin_events_enabled = t`,
> `admin_events_details_enabled = f`, `events_expiration = 2592000` and the
> `adminEventsExpiration` realm attribute `= 2592000`. Both halves of the step — the events-config
> resource *and* the separate realm attribute — are in place, which is the combination this section
> warns looks finished when only the first is done.
>
> Read with a `SELECT`-only session against `db-keycloak` rather than through `kcadm.sh`, so no
> credential was touched. The step below is kept as the record of what was applied and as the
> instruction for a rebuilt realm.
>
> Consequence now disclosed rather than pending: the store holds login and admin events with
> timestamps, accounts and IP addresses for 30 days, which
> [`docs/privacy/processing-activities.md`](privacy/processing-activities.md) records as part of A7
> and the privacy policy states in its security-monitoring section.

Before this was applied there was no login-failure, token-error or client-disable event anywhere:
the *detect* half of the security ladder was blind on the token endpoint.

**Console:**
- *Realm settings* → *Events* tab → *User events settings* → **Save events** on → **Expiration**
`30 days` → *Save*.
- *Realm settings* → *Events* tab → *Admin events settings* → **Save events** on → leave
**Include representation** **off** → **Expiration** `30 days` → *Save*.

**CLI — note the two different endpoints:**

```bash
# user + admin events: the events-config resource
kc update events/config -r iri \
    -s eventsEnabled=true -s eventsExpiration=2592000 \
    -s adminEventsEnabled=true -s adminEventsDetailsEnabled=false

# admin-events expiration: a REALM ATTRIBUTE, not part of the resource above
kc update realms/iri \
    -s 'attributes.adminEventsExpiration=2592000'
```

**Verify both, separately** — this is the step whose half-done state looks finished:

```bash
kc get events/config -r iri
kc get realms/iri --fields attributes \
    | grep adminEventsExpiration
```

**Rollback:** `… update events/config -r iri -s eventsEnabled=false -s adminEventsEnabled=false`.
Stored events remain until they expire.

**Why `Include representation` stays off:** it stores the JSON body of every admin REST call, which
is the one place in the event store where a credential could land.

**Expiration is seconds.** `2592000` = 30 d. `30` would be thirty seconds, and the console's day
picker hides the unit — which is why the verification above reads the raw value back.

---

## Step 5 — Clear the redirect/origin lists on the two service-account clients

> **Status:** done — applied by 2026-09-09 (realm export of that day; see *Status* above).

`backend-service` and `basetool-ingest-gateway` are service-account-only: they never perform a
browser redirect, so **every** entry on those two lists is a standing offer nobody needs — not only
the `/*`.

Before this step `backend-service` carried four redirect URIs including `/*` and
`http://backend:11261/*`, and four web origins. **Read the live values from
`kc-clients.before.json`** and clear both lists entirely. (Done: both lists are empty in the
2026-09-09 export.)

**Console:** *Clients* → the client → *Settings* → **Valid redirect URIs** / **Web origins** →
remove every row → *Save*.

**CLI:**

```bash
id=$(kc get clients -r iri \
     -q clientId=backend-service --fields id --format csv --noquotes)
kc update clients/$id -r iri \
    -s 'redirectUris=[]' -s 'webOrigins=[]'
```

Repeat for `basetool-ingest-gateway`.

**Verify:** `… get clients/$id -r iri --fields clientId,redirectUris,webOrigins` → both empty.

**Rollback:** re-set both lists from `kc-clients.before.json`, verbatim.

**If it goes wrong:** nothing observable changes in either direction, because neither client uses the
fields — which is exactly why the wildcards were never noticed. Do **not** take the silence as
evidence you edited the right client; check `clientId` in the verify output.

---

## Step 6 — `basetool-frontend`: require PKCE with `S256` ⚠ version-sensitive

> **Status:** done — applied by 2026-09-09 (realm export of that day; see *Status* above).

`basetool-frontend` is a **public** client (`publicClient: true`) with no PKCE attribute set. An
intercepted authorization code is redeemable without it.

> [!note] Superseded on production 2026-09-25 — `basetool-frontend` is confidential
> ADR-0001 is carried out: since 16:15 UTC that day production's client is confidential (client
> secret **and** PKCE `S256`, which stays required), so M-6 is closed there
> ([`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md)). The callout
> below describes the state before, and still the testing realm's.

> [!important] This step is the interim state, not the target — [ADR-0001](adr/0001-frontend-confidential-oauth2-client.md) is still pending
> The frontend is public **today**, and that is the code's answer, not a plan's:
> `frontend/src/main/resources/application.yml` registers the client with
> `client-authentication-method: none`, and the realm has `publicClient: true` with no secret.
> ADR-0001 (**Accepted — implementation pending**, 2026-05-20) decided to give it a client secret
> and has not been carried out; reading the ADR as the state of the system is how an earlier version
> of the knowledge base's client table came to call this client *confidential*.
>
> Spring Security sends PKCE automatically for a public client, so the login flow is already
> PKCE-protected in practice — what this step adds is that the **realm requires** it, which closes
> the downgrade: without the attribute an authorization request that simply omits the challenge is
> accepted. What neither gives you is the second factor at the token endpoint that ADR-0001 wants,
> so **audit finding M-6 stays open after this step**, and its runbook is
> [`OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md`](OAUTH2_CONFIDENTIAL_CLIENT_MIGRATION.md). Do not read
> a green step 6 as M-6 closed.

**Console (26.7):** *Clients* → `basetool-frontend` → *Settings* → **Capability config** →
**Require PKCE** on → **PKCE Method** → `S256` → *Save*. Both controls write the same client
attribute; the switch is "attribute non-empty" and the select is its value.

**CLI:**

```bash
id=$(kc get clients -r iri \
     -q clientId=basetool-frontend --fields id --format csv --noquotes)
kc update clients/$id -r iri \
    -s 'attributes."pkce.code.challenge.method"=S256'
```

**Verify:** `… get clients/$id -r iri --fields clientId,attributes | grep pkce`

**Rollback:** set the attribute to the empty string.

**Test the web login immediately after.** A client that advertises PKCE while its adapter sends no
verifier fails **at the token exchange, not at the redirect** — so the symptom is a login that gets
all the way back to the app and then errors, which does not look like a Keycloak change. Spring
Security's OAuth2 client sends the verifier for public clients, so this is expected to pass; test it
anyway, because the cost of being wrong is every member locked out of the web tool.

### Does this apply to the other clients?

It is the right question and the answer is not "yes, everywhere". PKCE protects the **authorization
code** of a redirect-based flow, so it is worth exactly what that flow is worth on the client in
question. Checked client by client against the code and the realm, 2026-09-08:

|                    Client                    |      Public       |        Code flow        |                    PKCE now                    |           This step           |
|----------------------------------------------|-------------------|-------------------------|------------------------------------------------|-------------------------------|
| `basetool-frontend`                          | yes               | **yes** — the web login | none                                           | **required** — the step above |
| `basetool-android`                           | yes               | **yes**                 | **`S256`** — verified on production 2026-09-09 | nothing to do                 |
| `basetool-sc-extractor`                      | yes               | **enabled, and unused** | none                                           | **see the finding below**     |
| `grafana`                                    | no — confidential | yes                     | none                                           | recommended, not required     |
| `backend-service`, `basetool-ingest-gateway` | no                | no — service accounts   | —                                              | not applicable                |

**`basetool-android` is sound, and the check is the script, not a hand-written read.**
`scripts/provision-keycloak-mobile-client.py` sets `pkce.code.challenge.method = S256` at creation
and re-asserts it under `--verify-only`, and the app sends `code_challenge_method=S256` on every
authorization request. Confirmed against **production** on 2026-09-09: the attribute is there, along
with the other seven the script writes.

Check it with the script rather than with a `get clients` line. It reads the full representation and
asserts nine things — the two DPoP-critical attributes, the marker role without which the policy
matches nothing, the realm-role scope, withheld `offline_access`, redirect wildcards — where a
hand-written read asserts one and can get that one wrong:

```bash
python3 scripts/provision-keycloak-mobile-client.py --realm iri --verify-only --profile prod
```

`python3 …` and not `./…`: the file is not executable in the production checkout. It writes nothing,
and it shells out to `kcadm` inside the container, so it must run **on the production host** and it
uses the session § 0.4 opened. A clean answer is one line: *"the client and its refresh-only DPoP
policy are in the intended state"*.

On the **Podman** production host two things differ. The script is not deployed there (the host has
no repository checkout; `/var/iri/code/scripts` carries only the operational scripts), so copy it
over first. And its default kcadm prefix is `docker exec -i keycloak …`, which does not exist on
that host. Pass the prefix through a shell inside the container, so the `--config` of § 0.4 lands
after the subcommand the script appends:

```bash
python3 provision-keycloak-mobile-client.py --realm iri --verify-only --profile prod \
  --kcadm-command "sudo -u iri podman exec -i keycloak sh -c 'exec /opt/keycloak/bin/kcadm.sh \"\$@\" --config /opt/keycloak/data/tmp/kcadm.config' kcadm"
```

> [!bug] `--fields attributes` reports `{ }` for a client that has eleven of them
> `--fields` is a **projection**, and a nested object needs the parenthesis-star form. `--fields
> clientId,publicClient,attributes` answers `"attributes" : { }` — which reads like "this client has
>
>> no attributes" and is nothing of the sort. `--fields 'clientId,publicClient,attributes(*)'` returns
>> them.
>
> This runbook asserted a production defect on the strength of that empty object (2026-09-09) and
> was about to send an operator into a write that detaches and re-attaches the DPoP policy, for a
> problem that did not exist. `--verify-only` is what caught it.
>
> **The general rule, and it bit twice in one session: a kcadm answer narrowed by `--fields` is a
> statement about the projection, and a `-q` that the endpoint does not implement is no statement at
> all.** Neither failure announces itself. When an answer is going to decide something, read the
> object whole.

**If it ever does come back short**, the obvious fix does not work: `PUT clients/{id}` — which is
what setting a client attribute is, from kcadm and from the Console alike — is refused while this
client's DPoP policy is attached, with `Invalid client metadata: DPoP token is disabled`. The
supported route is the same script without `--verify-only` (`--dry-run` first), which detaches the
policy, writes the client and re-attaches it.

**`grafana` is confidential**, so its client secret is the second factor a public client lacks.
Keycloak's own help text puts it as "public clients … should always require PKCE … it is also
recommended for confidential clients as an additional layer". Recommended is not required; if you
set it, Grafana's OAuth client must send a verifier, so test a Grafana login immediately after.

**`backend-service` and `basetool-ingest-gateway`** perform no browser redirect at all (step 5 is
about clearing the redirect lists they never use). PKCE has nothing to protect there.

> [!bug] Thirteenth item — the extractor has an authorization-code flow nobody uses
> `basetool-sc-extractor` authenticates with the **device authorization grant** (RFC 8628) and by
> design sends no PKCE: `DeviceGrantClient` says so in as many words — *"PKCE is not used because
>
>> the device-code itself is the proof-of-possession in this grant"* — and its `auth` package
>> contains no authorization-code client at all.
>
> But the realm has `standardFlowEnabled: true` on that client, with
> `redirectUris: ["http://localhost/*", "http://127.0.0.1/*"]` and no PKCE. So a public client
> carries an open, wildcard-loopback authorization-code flow that **nothing in the product drives**
> — the leftover of the flow the device grant replaced.
>
> Two ways to close it, and the choice is the owner's:
> 1. **Turn the unused flow off** — `-s standardFlowEnabled=false`. Removes the surface rather than
> hardening it, which is the better answer for something nothing uses. Verify a device-grant
> login still works afterwards; it does not traverse this flow, but assert it rather than assume.
> 2. **Require `S256`** on it, exactly as step 6 does for the frontend. Leaves the flow reachable.
>
> Not folded into the twelve because it is not one of them: decision D11 took a named list on
> 2026-09-05 and this was not on it. It is written here because this is where the question arises.
>
> **Decided 2026-09-22: option 1, and the redirect URIs go with it** (owner; ADR-0202 amendment 1).
> Re-read on `basetool-sc-extractor` `main` (`c6de57ff4`) that day: `DeviceGrantClient` sends only
> the `device_code` and `refresh_token` grants. The change is applied by
> `scripts/provision-keycloak-realm.py` — its target is `standardFlowEnabled: false` with no
> redirect URI — not by hand. Applied to production on 2026-09-23 (the provisioner's dry run
> planned exactly the #2007 changes; a second dry run afterwards was empty).

---

## Step 7 — `basetool-frontend`: drop the stale `http://backend:11261` entries

> **Status:** done — applied by 2026-09-09 (realm export of that day; see *Status* above).

An internal Docker hostname on a **browser** client's redirect list. A browser cannot reach it, so it
grants nothing today; it is a leftover that becomes a real redirect target the day that name
resolves in a browser's network.

**Console:** *Clients* → `basetool-frontend` → *Settings* → remove `http://backend:11261/*` from
**Valid redirect URIs** and `http://backend:11261` from **Web origins** → *Save*.

Leave the production entries (`https://<the real host>/*`, `…/login/oauth2/code/keycloak`) alone.
The reference also showed a duplicated production entry and `http://frontend:18081/*`; the duplicate
is cosmetic. **The compose-internal `http://frontend:18081` pair was retired on 2026-09-22** (owner
decision, ADR-0202 amendment 1): the frontend serves HTTPS only on 18081 and builds its redirect URI
from the forwarded public origin, so no real login can present it. The provisioner removes it on
its next production apply; the e2e realm keeps its own entries.

**Verify:** `… get clients/$id -r iri --fields redirectUris,webOrigins` — read the whole list and
confirm the production login URI is still there.

**Rollback:** re-add both strings verbatim from `kc-clients.before.json`.

---

## Step 8 — `basetool-sc-extractor`: `fullScopeAllowed` → `false`, with an explicit scope

> **Status:** done — applied by 2026-09-09 (realm export of that day; see *Status* above).

The extractor is a **public** client on members' desktops with `fullScopeAllowed: true`: its tokens
carry every realm role the holder has, `Admin` included.

**Console:** *Clients* → `basetool-sc-extractor` → *Client scopes* tab →
**`basetool-sc-extractor-dedicated`** → *Scope* tab → **Full scope allowed** off. Then assign, on
that same *Scope* tab, only the realm roles the extractor actually needs.

**CLI:**

```bash
id=$(kc get clients -r iri \
     -q clientId=basetool-sc-extractor --fields id --format csv --noquotes)
kc update clients/$id -r iri -s fullScopeAllowed=false
```

**Model it on `basetool-android`**, which already runs narrowed — but read that client's scope
mappings from the **live realm**, not from the reference export, which predates it:

```bash
aid=$(kc get clients -r iri \
      -q clientId=basetool-android --fields id --format csv --noquotes)
kc get clients/$aid/scope-mappings/realm -r iri
```

**Verify:** `… get clients/$id -r iri --fields clientId,fullScopeAllowed` → `false`.

**Rollback:** `-s fullScopeAllowed=true`.

**Check the extractor still ingests before calling this done.** A scope that is too narrow fails at
the ingest gateway's **audience check**, not at login — so the member signs in successfully and the
upload fails afterwards.

---

## Step 9 — Take the two audience scopes off everything that does not need them

> **Status:** done — applied by 2026-09-09 (realm export of that day; see *Status* above).

`extractor-ingest` and `extractor-ingest-only` are realm **default** client scopes, so they stamp the
backend and ingest audiences onto clients that have no business carrying them — `grafana` among them.
An audience claim is what a resource server trusts; handing it to every client makes the audience
check decorative.

**This step is two halves, and the second is the one that changes anything today.**

### 9a — the realm defaults (affects *newly created* clients only)

**Console:** *Client scopes* (left menu) → for `extractor-ingest` and `extractor-ingest-only` set
**Assigned type** to *None*.

**CLI:**

```bash
sid=$(kc get client-scopes -r iri \
      --fields id,name --format csv --noquotes | awk -F, '$2=="extractor-ingest"{print $1}')
echo "[$sid]"   # exactly one uuid, or stop here
kc delete default-default-client-scopes/$sid -r iri
```

> [!warning] `-q` does not filter this endpoint, and says nothing when it does not
> `-q` adds a **query parameter to the request**, so it filters only where the Admin API
> implements that parameter. `GET /clients` implements `clientId`, which is why `-q clientId=…`
> works elsewhere in this runbook. `GET /client-scopes` implements **no** `name` parameter, so
> `-q name=extractor-ingest` is accepted, ignored, and returns **every** scope in the realm — 17
> of them. The variable then holds a list and kcadm fails on the next command with `Unmatched
> arguments from index 2: '<uuid>', '<uuid>', …`, which reads like a quoting bug rather than a
> filter that never ran. Reported from the production console, 2026-09-09.
>
> Hence the filter above is client-side and keyed on an **exact** name: a substring match would
> also take `extractor-ingest-only`, a different scope with a different disposition below. And
> hence the `echo` — resolve, look, then delete.

Repeat for `extractor-ingest-only`.

**Verify:** `… get default-default-client-scopes -r iri` — neither name present.

### 9b — the clients that already carry them

Removing the realm default leaves every existing client exactly as it was. Work from
`kc-clients.before.json`; as of the reference the assignment was:

|          Client           | `extractor-ingest` | `extractor-ingest-only` |                 Keep?                 |
|---------------------------|--------------------|-------------------------|---------------------------------------|
| `basetool-sc-extractor`   | yes                | yes                     | **both**                              |
| `basetool-frontend`       | yes                | no                      | **`extractor-ingest`**                |
| `basetool-ingest-gateway` | yes                | yes                     | decide against its own audience needs |
| `grafana`                 | yes                | no                      | **remove**                            |

**Console:** *Clients* → the client → *Client scopes* tab → remove the scope from the assigned list.

**CLI:**

```bash
gid=$(kc get clients -r iri \
      -q clientId=grafana --fields id --format csv --noquotes)
kc delete clients/$gid/default-client-scopes/$sid -r iri
```

**Verify — one token per affected client**, not just the config:

```bash
kc get clients/$gid/default-client-scopes -r iri
```

and then an actual login through Grafana.

**Rollback:** these two paths answer `PUT` but not `GET`, so they need `-n` (no-merge, documented
as exactly this case) or `update` fails trying to read the current value first:

```bash
# realm half
kc update default-default-client-scopes/$sid -r iri -n
# one client
kc update clients/<id>/default-client-scopes/$sid -r iri -n
```

**If it goes wrong:** a missing audience is refused by the resource server with a **401 that reads
like an expired token**. If Grafana logins break right after this step, this step is why.

---

## Step 10 — Drop `offline_access` from `default-roles-iri`

> **Status:** done — applied by 2026-09-09 (realm export of that day; see *Status* above).

Every account can currently mint an offline token, which outlives every session policy in the realm.
The composite holds `offline_access`, `uma_authorization`, `KRT Member` and two `account` client
roles.

**Console:** *Realm settings* → *User registration* tab → **Default roles** → remove
`offline_access`.

**CLI:**

```bash
kc remove-roles -r iri \
    --rname default-roles-iri --rolename offline_access
```

**Verify:**

```bash
kc get roles/default-roles-iri/composites -r iri \
    --fields name
```

**Rollback:** `… add-roles -r iri --rname default-roles-iri --rolename offline_access`

> [!danger] Check the Android app's refresh **before** running this
> If the app relied on an **offline** token rather than an ordinary refresh token, this step signs
> every installation out. It should not: ADR-0131 / REQ-SEC-030 bind the app to a refresh-token-only
> DPoP policy, and the provisioning script explicitly withholds `offline_access` from the client
> (`delete clients/<uuid>/optional-client-scopes/<id> — offline_access withheld`). Confirm that is
> still true on the live client before you remove the realm default:
>
> ```bash
> kc get clients/$aid/optional-client-scopes -r iri
> ```
>
> `offline_access` must **not** be in that list. Then open the app once after the change.

---

## Step 12 — Decide the session windows

> **Status:** **open** — the values in the table below are those of 2026-09-09; no decision has
> been recorded. Record it here, and whether the app's per-client overrides followed.

Not a defect: a decision that has never been made explicitly. Current values, in seconds:

|              Setting              |    Now     |       |
|-----------------------------------|------------|-------|
| `ssoSessionIdleTimeout`           | `2592000`  | 30 d  |
| `ssoSessionMaxLifespan`           | `15552000` | 180 d |
| `ssoSessionIdleTimeoutRememberMe` | `2592000`  | 30 d  |
| `ssoSessionMaxLifespanRememberMe` | `15552000` | 180 d |
| `rememberMe`                      | `true`     |       |

The frontend's own session is 720 h; the realm's windows are what actually decide how long a stolen
browser stays useful. A 180-day maximum means a device compromised in March still holds a session in
September.

**Console:** *Realm settings* → *Sessions* tab (**SSO Session Idle**, **SSO Session Max**, and the
two *Remember me* variants).

> [!important] The realm is not the only place these numbers live
> `basetool-android` carries its **own** overrides — `client.session.idle.timeout: 2592000` and
> `client.session.max.lifespan: 15552000`, the same 30 d / 180 d, written by its provisioning script
> and read off production on 2026-09-09. Shortening the realm here leaves those two standing, and
> they are what that client's sessions actually use. If the decision is "sessions get shorter", it
> has two homes: this tab, and a provisioning-script run for the app. Change the realm first, then
> re-run the script's `--verify-only` — it clamps its values against the realm bounds and says so.

**CLI:** `… update realms/iri -s ssoSessionMaxLifespan=<seconds>` etc.

**Verify:** `… get realms/iri --fields ssoSessionIdleTimeout,ssoSessionMaxLifespan,ssoSessionIdleTimeoutRememberMe,ssoSessionMaxLifespanRememberMe,rememberMe`

**Rollback:** the four numbers above.

**If it goes wrong:** shortening these logs members out sooner than they expect, which is a support
question and not an outage. The Android app refreshes against the same windows — a max lifespan
below the app's usage gap means a member reopening the app after that long must sign in again.

---

## Step 11 — Require OTP for holders of `Admin` — **run this last**

> **Status:** **open, not started** — on 2026-09-09 the realm still bound the built-in `browser`
> flow and the `discord` provider had no `postBrokerLoginFlowAlias`.

Two halves, and the incomplete version looks finished: the browser flow alone is half the gate,
because an admin who signs in **through Discord** never traverses it. The realm's `discord` provider
currently has `postBrokerLoginFlowAlias: null`.

### 11a — the browser flow

**Console:** *Authentication* → *Flows* → the row menu on **browser** → **Duplicate** (never edit a
built-in flow in place). Then, inside the copy's `forms` sub-flow:

1. **Add sub-flow** — not *Add step*. Name it e.g. `Admin OTP` and set its requirement to
   **Conditional**.
2. Inside it, **Add condition** → **Condition - User Role**. Two fields: *Alias* (a name for the
   execution, e.g. `is-admin`) and *User role* — `Admin`. A **client** role would be written
   `clientname.rolename`; `Admin` is a realm role, so the bare name is right.
3. Inside the same sub-flow, **Add step** → **OTP Form**, requirement **Required**.
4. On the flow's own page: the action menu → **Bind flow** → the **Choose binding type** dialog →
   **Browser Flow** → *Save*.

A *Conditional* sub-flow acts as *Required* when all its conditions evaluate true, and is treated as
*Disabled* otherwise — which is what makes this gate admins and nobody else.

### 11b — the Discord identity provider

**Console:** *Identity providers* → `discord` → **Post login flow** → select the flow from 11a →
*Save*.

**Verify — both paths, with a real admin account:**
1. Sign in with username + password → OTP is demanded.
2. Sign in through Discord → OTP is demanded.
3. Sign in as a **non-admin** member both ways → no OTP.

**Rollback:** set the sub-flow's requirement to **Disabled** and unbind the post-login flow
(*Identity providers* → `discord` → **Post login flow** → *None*).

> [!danger] Keep a second admin session open in another browser while you do this
> An OTP flow that is wrong locks the console, and the console is where you undo it. Do not close
> the working session until step 11's verification has passed in a *third* place — a private window.
>
> And note § 0.3: after this step your admin account can no longer authenticate to kcadm. That is
> the intended outcome, and it is why this step is last.

---

## After the twelve

1. **Delete the provisioning identity** created in § 0.3. ***Clients*** → `basetool-provisioner`
   → the client's action menu → **Delete**, and confirm. Deleting the client removes its service
   account and the role assignments with it; there is no second cleanup.

   It exists to be used for minutes, not to sit in the realm holding `manage-realm` — a credential
   that outlives its procedure is one nobody is watching. If you would rather keep it for a planned
   second pass, **disable** it instead (*Settings* → *Enabled* → Off): a disabled client cannot
   obtain a token, so the standing grant is inert until you re-enable it. Delete it when the pass is
   done either way.

   **Verify it is gone**, rather than assuming the click landed — the read must come back empty:

   ```bash
   kc get clients -r iri \
       -q clientId=basetool-provisioner --fields clientId
   ```

   This is also the point at which your own kcadm session stops working, which is the intended
   outcome and not a fault.

2. **Remove the kcadm session file:**
   `sudo -u iri podman exec keycloak rm -f /opt/keycloak/data/tmp/kcadm.config` — it holds the
   truststore password and a token in cleartext.

3. **Re-export and commit the sanitized realm**, so this state is version-controlled rather than
   living only in the console:

   ```bash
   python scripts/sanitize-realm-export.py <export.json> docs/keycloak/realm-config.reference.json
   ```

   > [!bug] It does **not** version-control step 11's flow, and this runbook said it did
   > The sentence here used to read *"the sanitizer keeps `authenticationFlows`, `authenticatorConfig`
   >
   >> and `requiredActions` since WP-K1"*. It does not: all three are in `DROP_SECTIONS` in
   >> `scripts/sanitize-realm-export.py`, and that file has two commits, neither of which is the
   >> change the sentence describes. Verified 2026-09-09 while regenerating the reference — the run
   >> reports `dropped:authenticationFlows: 28`.
   >
   > So **step 11's flow lives only in the Admin Console**, and nothing in this repository records
   > its shape. That is the one step whose outcome is a structure rather than a value, and it is the
   > one the re-export does not capture.
   >
   > Keeping the three sections is not a one-line change to make in passing: `authenticatorConfig`
   > is where an authenticator's own configuration lives, and this realm's `discord-guild-role-gate`
   > has config of its own. Whether any of it is sensitive has to be established before the section
   > is committed, not assumed — which is presumably why it was dropped in the first place. Until
   > somebody does that, write step 11's flow down by hand: the sub-flow name, its requirement, the
   > condition's `User role`, the OTP step's requirement, and the binding.

4. **Close the open finding in [`docs/keycloak/README.md`](keycloak/README.md)**, which records
   `fullScopeAllowed: true` on the frontend and the extractor. Step 8 closes the extractor half. The
   frontend half is deliberately **not** on this list: it is a public browser client whose scope is
   the member's own roles, and narrowing it is ADR-0001's confidential-client migration, not a
   hardening step.

> [!note] Nothing here is required for the members-only release to be correct
> The release stands on its own — REQ-SEC-052 and REQ-SEC-053 are enforced in the application and
> the sweeps assert them. These twelve reduce the blast radius *around* it: what an intercepted code
> is worth, what a token carries, and whether anyone can see it happen.
