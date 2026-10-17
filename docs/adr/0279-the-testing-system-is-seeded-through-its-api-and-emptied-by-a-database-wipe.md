# ADR-0279 — The testing system is seeded through its API and emptied by a database wipe

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** @greluc
- **Related:** spec `REQ-OPS-050` ([`deployment-delivery.md`](../specs/deployment-delivery.md)) ·
  spec `REQ-SEC-024` (the `azp` allow-list, [ADR-0270](0270-the-backend-accepts-tokens-only-from-named-clients.md)) ·
  [ADR-0202](0202-a-realm-is-brought-to-the-production-shape-by-a-provisioner-that-never-deletes.md) ·
  `scripts/seed-briefing.py`, `scripts/testing-reset.sh`, `scripts/provision-keycloak-realm.py` ·
  issue #2639

## Context

The briefing of #2639 walks members through every user-facing function in the testing system and
then lets them try each one on their own account. It needs a populated testing system — an org
structure, presenter accounts, a participant pool with role grants, and data in every feature area —
and a way back to an empty one afterwards, because participants create data of their own during the
hands-on.

The issue decided that a script against the testing system's API creates the data, so audit,
permissions and versioning apply as for real users. Three facts shape how:

- **Most of the data can only be created as the user it belongs to.** Joining a mission, an offer on
  the Materialbörse, a pledged donation, a bank request: there is no admin route "on behalf of" for
  these. The script has to sign in as each account.
- **No client of a production-shaped realm allows a password sign-in.** The provisioner pins
  `directAccessGrantsEnabled` off for every client, and the testing realm has that shape.
- **The backend accepts a token only from a configured client** (`azp`, ADR-0270), by default
  `basetool-frontend` and `basetool-ingest-gateway`.

## Decision

**A testing-only `basetool-seed` client signs the seed in; the Basetool database of the testing host
is dropped to empty it again.**

1. **The seed client exists only where asked for, and never for production.** The provisioner
   manages a confidential `basetool-seed` client — password grant and a service account with
   `manage-users`, `query-users`, `view-users` and `view-realm`, the backend audience through the
   `extractor-ingest` scope — only with `--seed-client`, and refuses that flag before it reads the
   realm when the public origin is a production host. Its secret is read from the Admin Console into
   `$SEED_CLIENT_SECRET` on the workstation that runs the seed; it goes into no host `.env`. A later
   run without the flag reports the client and keeps it (ADR-0202).
2. **The testing backend accepts it by configuration.** The testing host's `.env` sets
   `IRI_BACKEND_ACCEPTED_CLIENT_IDS=basetool-frontend,basetool-ingest-gateway,basetool-seed`.
   Production keeps the default; the release changes no default.
3. **The seed works through the API only.** `scripts/seed-briefing.py seed` creates the Keycloak
   accounts with the service account, signs in as each of them, approves the pending registrations
   and accepts the terms, and then builds the org structure and the feature data domain by domain. A
   domain that fails is reported and the next one runs. A second run reuses the accounts and their
   passwords and skips the data once the test org structure exists; `--only` repeats chosen domains.
   Every URL that names a production host is refused.
4. **The seed accounts are marked by their e-mail domain**, `@briefing.invalid`. Keycloak 26's user
   profile drops attributes it does not declare, so a marker attribute would need a realm change; the
   domain needs none. `purge` deletes exactly these accounts, and `seed` refuses to take over an
   existing account outside the domain.
5. **The passwords leave the run only as files outside the repository.** The participant handout and
   the presenter list are CSV files in `--out`, which the script refuses inside the repository.
6. **Emptying the testing system drops and recreates the Basetool database.**
   `scripts/testing-reset.sh --wipe-testing-database` refuses unless the host's `.env` sets
   `APP_INSTANCE_TEST_SYSTEM=true`, takes the deploy lock, stops the three writers, drops and
   recreates the database, and starts them again; Flyway rebuilds the schema. Keycloak, Redis and
   every other volume stay. The seed accounts go with `seed-briefing.py purge`.
7. **The reset script is not installed by the host role.** It is copied to the testing host for the
   run, with `scripts/lib/`, as the realm provisioner already is. Installing it through the role
   would put a wipe script onto the production host and, through `host-scripts.list` (ADR-0247),
   block every promotion until production applied the role again.

## Consequences

- The seeded data is ordinary data: every write is permission-checked, versioned and audited. Seed
  writes carry the change source `system` (a first-party web client is only `basetool-frontend`) and
  the bounded API-client label for an unlisted client.
- A participant signs in with a username and password on the Keycloak page, not through Discord, and
  holds exactly the role the track needs. No real name or Discord identity enters the testing system
  through the seed.
- The wipe removes everything participants created and the session's audit trail with it, and
  leaves every other testing-host setting in place. Accounts that signed in through Discord stay in
  Keycloak; their Basetool user is recreated on their next request.
- The testing realm carries one more client than production. The provisioner's report lists it on
  every run without the flag, which is the visible trace that it exists.
- Two things remain manual: the testing `.env` change of decision 2, and one device-grant connection
  of the test connected-application client, which the profile demo's consent and undo need and which
  no API can create.
- The data depends on the testing catalogue (ship types, refinery locations, materials). The seed
  creates a minimal set of materials, frequency types and job types where the catalogue is empty and
  reports a domain it cannot build, such as the refinery without a refinery location.

## Alternatives considered

- **Seeding with admin routes only.** No new client — but the Materialbörse, donations, mission
  sign-ups and bank requests have no admin route, and the briefing shows exactly those.
- **Seeding only a local stack.** The committed test realms already allow a password grant — but the
  participants of a streamed session cannot reach a workstation's local stack.
- **Live sign-up through Discord.** Participants would bring their real identities into the testing
  system, and every approval, Staffel and role grant would have to be done by hand during the
  session.
- **A database snapshot before the seed and a restore after.** Restores the testing host to its exact
  previous state, but needs dumps of two databases on the host and a restore procedure for each
  session; the owner preferred an empty testing system over its previous contents.
- **Removing the seeded rows through the API by manifest.** Leaves whatever participants created and
  the audit trail behind, which is most of what needs removing.
- **Discarding every testing volume, Keycloak included.** The realm holds the Discord identity
  provider and client secrets that would have to be provisioned and entered again; the Basetool
  database alone is what the briefing fills.
