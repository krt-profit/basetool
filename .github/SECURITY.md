# Security Policy

Thank you for taking the time to look at the security of Profit Basetool.
This document explains how to report a vulnerability, which versions receive
fixes, and what you can expect from us in return.

## Reporting a Vulnerability

**Please do NOT open a public GitHub Issue, Discussion, or Pull Request for
anything you believe is security-sensitive.** Public disclosure before a fix
is available puts every operator running this project at risk.

The preferred channel is GitHub's **Private Vulnerability Reporting** via
Security Advisories:

> **[Report a vulnerability](https://github.com/krt-profit/basetool/security/advisories/new)**

That form gives us a private, structured place to triage the finding,
collaborate on a patch, request a CVE, and coordinate disclosure with you.

A good report typically includes:

- A clear description of the issue and its impact.
- The affected version, commit SHA, or container image digest.
- The affected component (`backend`, `frontend`, `keycloak-theme`,
  `keycloak-spi`, `ingest`, `scripts/`, GitHub Actions workflow, container
  image, etc.).
- Reproduction steps, a proof of concept, or a minimal test case.
- Any relevant configuration (Spring profile, Keycloak realm settings,
  reverse-proxy setup) needed to trigger the issue.
- Your assessment of severity (CVSS vector welcome but not required).
- Whether you intend to publish your own write-up, and on what timeline.

If you cannot use GitHub Security Advisories for some reason, please open a
minimal public issue that says only *"I would like to report a security
issue, please contact me"* — without any technical detail — and we will reach
out privately to arrange a channel.

## What to Expect

This is a community-maintained project, so we cannot offer a commercial SLA,
but we aim for the following turnaround on every report:

|                          Step                          |          Target           |
|--------------------------------------------------------|---------------------------|
| Acknowledge receipt of the report                      | within 7 days             |
| Initial triage and severity assessment shared with you | within 14 days            |
| Fix in `main` for High / Critical issues               | within 90 days            |
| Coordinated public disclosure after a fix is available | within 14 days of release |

If we cannot meet one of these targets we will tell you why and propose a
new date in the advisory thread. We will credit you in the published
advisory and the [`CHANGELOG.md`](../CHANGELOG.md) unless you ask us not to.

## Supported Versions

Profit Basetool is currently in the `0.x` release line and under active
development. Only the latest minor release receives security fixes; older
`0.x` minors do not get backports.

|                          Version                          |                        Supported                        |
|-----------------------------------------------------------|---------------------------------------------------------|
| Latest `0.x` minor on `main`                              | :white_check_mark:                                      |
| Older `0.x` releases                                      | :x: (please upgrade)                                    |
| Container tags `:edge`, `:sha-<short>`, `:stable`         | :white_check_mark:                                      |
| Container tags `:1`, `:1.4`, `:1.4.2`, `:latest` once cut | :white_check_mark: for the *current* major / minor only |

Once the project reaches `1.0`, this table will be updated to define a clear
support window across major versions.

## Scope

The following are **in scope** for this policy:

- Source code in this repository (`backend/`, `frontend/`, `keycloak-theme/`,
  `keycloak-spi/`, `ingest/`, `scripts/`, build configuration, Flyway
  migrations).
- Published container images under
  [`ghcr.io/krt-profit/basetool-backend`](https://github.com/krt-profit/basetool/pkgs/container/basetool-backend),
  [`ghcr.io/krt-profit/basetool-frontend`](https://github.com/krt-profit/basetool/pkgs/container/basetool-frontend),
  and
  [`ghcr.io/krt-profit/basetool-ingest`](https://github.com/krt-profit/basetool/pkgs/container/basetool-ingest)
  (the internet-facing gateway).
- GitHub Actions workflows under `.github/workflows/` and their pinned
  actions.
- The Docker Compose definitions and deployment scripts shipped in this
  repository.
- The default Keycloak realm export and theme shipped here, when used as
  documented.

The following are typically **out of scope**:

- Vulnerabilities in third-party dependencies that do not have a viable fix
  path in this project. Please report those upstream first; you are still
  welcome to let us know so we can track an upgrade.
- Misconfiguration of a self-hosted deployment that deviates from the
  documented setup (e.g. exposing the backend without TLS, running with
  `ddl-auto=update`, reusing example credentials in production).
- Findings that require an already-compromised host, an already-stolen JWT,
  or physical access to the operator's machine.
- Denial-of-service achievable only by a single authenticated user against
  their own tenant data and that cannot affect other users.
- Reports generated solely by automated scanners without a demonstrated,
  reproducible impact on this codebase.
- Social engineering of contributors or operators, and any attack against
  infrastructure we do not control (Keycloak, PostgreSQL, Redis, GitHub,
  GHCR, the UEX API, etc.).
- Best-practice suggestions without a concrete vulnerability (e.g. "you
  should add header X"). These are very welcome as regular issues or pull
  requests instead.

Particularly interesting classes of issue, given the project's architecture:

- Cross-tenant data access — any read or write that bypasses the JWT `sub`
  filter or the elevated-role check in the service layer.
- Information disclosure to unauthenticated guests (see the
  `cleanupForGuest`-style helpers — anything leaking email, real name or
  internal orders/items is in scope).
- Authentication or authorisation bypass against any `@RestController` or
  Thymeleaf view, including missing `@PreAuthorize` annotations.
- Optimistic-locking bypass leading to lost writes or privilege escalation.
- Injection (SQL, JPQL, Thymeleaf expression, OS command, template, log).
- SSRF via the UEX integration, the WebClient, any administrator-supplied
  URL, or the internet-facing `ingest` gateway.
- Discord federation membership-gate bypass (`keycloak-spi`) — any
  authentication bypass that allows login without "DAS KARTELL" guild
  membership or the required `KRT-Mitglied` role check.
- Sensitive data appearing in logs (names, emails, tokens, JWTs, password
  hashes) — these MUST never be logged.
- Supply-chain integrity issues affecting our build, signing, or publishing
  pipeline (`release-images.yml`, `promote.yml`).

## Coordinated Disclosure

We follow coordinated disclosure. Our default disclosure window is **90 days
from the date we acknowledge the report**, or sooner if a fix and a release
are already public. If a fix is not yet available after 90 days we will
agree on an extension with you in the advisory thread before any public
disclosure.

When the advisory is published we will:

1. Release a patched version and the corresponding container image tags.
2. Publish the GitHub Security Advisory with details, affected versions,
   workarounds, and credit.
3. Add a `### Security` entry to [`CHANGELOG.md`](../CHANGELOG.md) referring
   to the advisory.

## Safe Harbor

We will not pursue or support legal action against researchers who:

- Make a good-faith effort to comply with this policy.
- Avoid privacy violations, data destruction, and service degradation
  against operators of this software or its users.
- Use only their own test data, or data they have explicit permission to
  access, and stop as soon as they have demonstrated the issue.
- Give us reasonable time to remediate before any public disclosure.

If a third party initiates legal action against you for activity that
complied with this policy, we will make our position on safe harbor known.

This policy does not authorise you to test the security of any third-party
service used by this project (Keycloak, PostgreSQL, Redis, GitHub, GHCR,
UEX, etc.), nor any specific deployment operated by someone other than
yourself. Always obtain authorisation from the operator before testing a
live instance.

## Verifying Releases

The build pipeline produces signed, attested artefacts. If you want to
verify that a container image actually came from this repository's CI
before reporting a finding against it:

- **Signature** — every image is signed keyless with
  [Sigstore Cosign](https://docs.sigstore.dev/cosign/overview/). The
  signing identity is the `release-images.yml` workflow on a ref under
  `refs/heads/main` or `refs/tags/v*.*.*`, issued by
  `https://token.actions.githubusercontent.com`.
- **Provenance (in the registry)** — SLSA build provenance
  (`provenance: mode=max`) is attached as an OCI attestation to every
  published manifest.
- **Provenance (independent of the registry)** — the same build is also
  attested to GitHub's own attestation store, which is written under a
  different permission than the registry (`attestations: write`, not
  `packages: write`). This is the copy that survives a registry-side
  rewrite of a package, so prefer it when the question is *"did this
  organisation's CI really build this digest?"*:

  ```bash
  gh attestation verify oci://ghcr.io/krt-profit/basetool-backend:1.6.3 --repo krt-profit/basetool
  ```

  The same works for `basetool-frontend`, `basetool-ingest`,
  `basetool-config` and `basetool-keycloak-spi`.

- **SBOM** — an SPDX SBOM is attached as an OCI attestation; CycloneDX
  SBOMs are also generated locally via `./gradlew cyclonedxBom` and
  shipped under `<module>/docs/`. The four CycloneDX files attached to a
  GitHub Release carry their own build provenance — verify a downloaded
  one before you trust what it lists:

  ```bash
  gh attestation verify backend-bom.json --repo krt-profit/basetool
  ```

A finding that requires bypassing these verification steps is itself in
scope.

## Thank You

Security research is real work and we appreciate it. If you report
something that turns out to be valid, we will credit you in the published
advisory and the changelog by name, handle, or anonymously — your choice.
