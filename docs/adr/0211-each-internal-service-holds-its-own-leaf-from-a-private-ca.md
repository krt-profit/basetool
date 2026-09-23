# ADR-0211 — Each internal service holds its own leaf from a private CA

- **Status:** Accepted — shipped inert; the production rollout awaits the owner
- **Date:** 2026-09-23
- **Deciders:** @greluc
- **Requirement:** [REQ-SEC-070](../specs/security-and-access.md)
- **Related:** [ADR-0139](0139-shared-committed-tls-material-for-the-test-stack.md) (the test
  material, amended), [ADR-0204](0204-backend-and-ingest-call-http-through-restclient-without-webflux.md)
  §6 (the relay's hostname opt-out), [ADR-0162](0162-edge-is-native-nginx-with-a-separate-acme-client.md) (the edge
  verifies upstreams), [ADR-0188](0188-the-host-bootstrap-is-an-ansible-role.md)
  (the role installs the tooling), REQ-SEC-014, REQ-OPS-016, REQ-OBS-008
- **Source:** improvement audit 2026-09-22, finding ING-SEC-04

## Context

One self-signed `keystore.p12` is the TLS identity of backend, frontend, ingest and Keycloak, and
because it is self-signed it is also the anchor every one of them — and the edge, Prometheus and the
blackbox exporter — trusts. Two things follow.

- **One key opens every door.** The ingest gateway is the only new internet-facing service, and its
  container holds the private key that the backend and Keycloak serve too. A read of that file
  (a path traversal, a dependency with a file-read primitive, a debug endpoint left on) hands an
  attacker on an internal network the backend's and Keycloak's identity, with nothing to tell the
  forgery from the real thing.
- **The name check could not be worth anything.** With one certificate carrying every service's name,
  a hostname check can only ever pass, so the frontend's backend client and the ingest relay were
  written without one (M-13, ADR-0204 §6). The pin was the whole guarantee.

## Decision

1. **A private CA signs one leaf per service** — backend, frontend, ingest, Keycloak — each naming
   only its own docker alias plus `localhost`/`127.0.0.1` (the container-local healthchecks). The CA
   is `CA:TRUE, pathlen:0`; leaves carry `serverAuth` only.
2. **The CA key is destroyed at the end of the run** (`scripts/mint-internal-tls.sh`). No certificate
   can be added to the set later, so a stolen leaf key is the only thing a compromise yields, and a
   rotation re-mints everything together — the same coordinated change the shared keystore always
   was. The alternative, keeping the CA key on the host, would make that file the new single key that
   opens every door.
3. **Clients pin the CA and check the name.** A CA-only truststore is mounted as
   `/run/secrets/internal-truststore.p12` (`INTERNAL_TLS_TRUSTSTORE`) and every internal SSL bundle
   pins it. The frontend's backend client, its readiness probe and the ingest relay verify the
   hostname when `INTERNAL_TLS_VERIFY_HOSTNAME` is true — ADR-0204 §6's opt-out becomes a switch.
   The backend's Keycloak client, the edge, Prometheus and the blackbox module already verified.
4. **The script is POSIX `sh` plus `keytool`** and runs inside the backend image on the host, which
   has no JDK — the way the keystore rotation always ran. The owner runs it; nothing deploys it.
5. **The release is inert, the rollout is staged.** Every new mount falls back to the shared keystore
   and every switch defaults to today's behaviour, so merging and deploying changes nothing. The
   rollout (docs/deployment.md, *Internal TLS*) widens trust first — the CA **and** the old
   certificate in every anchor — then switches the servers with a release that bakes the new paths,
   then narrows trust to the CA. No step depends on the order in which services restart, and each
   has a rollback that needs no re-mint.
6. **The committed test material takes the same shape** (ADR-0139 amendment 1), minted by the same
   script, so CI exercises the trust relationships production will have.
7. **The deploy pre-flight checks every PKCS#12 a unit mounts**, not the first one, because one
   missing file among five is exactly the failure a per-service layout introduces.

## Consequences

- A leaked leaf impersonates one service, to the clients that dial that name. The ingest's key no
  longer opens the backend or Keycloak.
- The hostname check becomes a security boundary rather than a formality, so a leaf minted without
  a name a peer dials fails that peer's handshake — loudly, at restart, and the frontend's readiness
  follows the same switch so the failure is a failed restart rather than a quiet 502 later.
- Rotation cost is unchanged (everything at once), and the CA's expiry is the one date to watch;
  `iri-cert-expiry` already reads `basetool-ca.crt`, which becomes the CA.
- The Keycloak SPI precheck's hand-built truststore and the Android dev build's bundled test anchor
  follow the new CA; both are listed in the rollout and in ADR-0139's amendment.
- `/var/iri/secrets/tls` is one more directory in the backup, captured best-effort like the keystore.

## Alternatives considered

- **Per-service self-signed certificates, each pinned individually.** No CA key to destroy, but every
  client would pin every peer it talks to, and every rotation would touch every truststore. A CA with
  its key destroyed gives the same "nothing new can be issued" guarantee with one anchor.
- **Keep the CA key on the host** for single-leaf re-issue. Rejected: see decision 2.
- **mTLS between services.** Out of scope for ING-SEC-04, which is about the servers' identities; the
  per-service leaves carry `serverAuth` only and would need `clientAuth` and client-side key material
  for that. Nothing here makes it harder later.
- **Switch everything in one release.** Rejected: a release that mounts files the host does not have
  yet cannot start, and one that swaps anchors and certificates at once breaks every handshake for as
  long as the rolling restart takes.
