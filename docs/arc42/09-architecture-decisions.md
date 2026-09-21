# 9. Architecture decisions

## 9.1 How decisions are recorded

Every architecturally significant decision is an **ADR** in [`docs/adr/`](../adr/README.md), written
before or with the change that implements it. There are **194** of them. They are numbered
sequentially, carry a status, and are amended in place with a dated `## Amendment N` section rather
than silently rewritten — a vault or a decision log that edits its own history teaches its readers
not to trust it.

Two rules make the collection usable rather than merely large:

- **A decision that is reversed is recorded as reversed**, with what was measured. Several ADRs here
  exist mainly to say *"we believed X, we measured, X was wrong in both directions"* — that is the
  most valuable kind of entry and the easiest to omit.
- **Claiming a number is done at push time**, against `origin/main` *and* open PRs. A number
  reserved only in prose does not exist as far as the numbering gate is concerned.

The ADRs are the *why*. The [`docs/specs/`](../specs/INDEX.md) registry is the *what must hold*, and
the knowledge-base vault is the *what is actually true on the running system right now*.

## 9.2 The decisions that shape the whole system

Not a ranking of importance — a reading list. Someone who understands these twelve can place almost
any other decision in context.

| ADR | Decision | What it determines |
| --- | --- | --- |
| **0049** | Host configuration is delivered as a promotable, signed OCI artifact | Configuration travels the same pull-only, digest-pinned, deliberately-promoted channel as the images. There is no "edit the config on the box". |
| **0055** | The Keycloak provider JAR is a *separate* promotable OCI artifact | Provider JARs are barred from the config bundle, so a provider change cannot ride in on a configuration promotion. |
| **0088** | Two-tier session idle timeout — and the redis ACL incident | Short idle for anonymous, 30 days after login. Its record also carries the `default`-user defect: an `aclfile` that omits `default` makes redis reset that user to `nopass`, and the compose comment that said otherwise was backwards. |
| **0139** | Shared, committed TLS material for the test stack | Why a deliberately worthless, published throwaway is an *application* of the no-real-credentials rule and not an exception to it. |
| **0159** | No anonymous or guest surface; every account is a member | The public surface is the landing page and the legal pages. This is the single decision most of the probe fleet exists to defend. |
| **0162** | The edge is native nginx, configured from git, with ACME in a separate container | Replaces the previous proxy whose configuration lived in a host database nobody could review. The edge is now diffable. |
| **0163** | The container runtime becomes rootless Podman on Rocky Linux 10, on a rebuilt host | The whole of §7. |
| **0166** | Identity moves onto the app origin, so the installed app can sign in | Keycloak is served under a path on the frontend host rather than its own vhost — which is why a fifth certificate directory exists that nothing serves. |
| **0188** | The host bootstrap is an Ansible role; provisioning stays separate from delivery | A rebuilt host is reproducible, and the deploy path does not have to double as a provisioner. |
| **0189** | Stateful containers run as their own uid, not as root that steps down | Measured against the assumption: the capability set REQ-OPS-014 assumed was wrong in *both* directions, and reducing redis's capabilities would have **raised** its privilege. |
| **0190** | Every container but Keycloak runs on a read-only root filesystem | Also measured against the assumption that the JVM and the databases write all over the filesystem. They do not. |
| **0192 / 0193** | Lombok and the JetBrains annotations everywhere; one logging facade, enforced | Style rules turned into compile errors and CI gates, so they stop depending on review attention. |

## 9.3 Where a decision is *not* an ADR

- **Feature behaviour** is a spec (`REQ-<AREA>-NNN`), not an ADR. "Which role may clear a payout" is
  a requirement; "authorisation is centralised on `@PreAuthorize`" is a decision.
- **Operational values** — a host, a port, a retention window, a threshold — belong in the knowledge
  base, where they can be corrected the day they change without rewriting a decision record.
- **Plan documents** (`docs/*_PLAN.md`) carry a `Doc type:` header marking them *living spec* or
  *historical plan*. A plan is frozen and pointed at the living truth once it ships, rather than
  being left to read as current.
