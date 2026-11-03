# ADR-0325 — Dependencies are checked against the GitHub Advisory Database, not the NVD

- **Status:** Accepted
- **Date:** 2026-11-02
- **Deciders:** @greluc
- **Supersedes:** [ADR-0324](0324-the-dependency-scan-reads-a-cached-nvd-feed-refreshed-on-its-own-schedule.md)
- **Related:** REQ-OPS-054 · REQ-OPS-024 · REQ-OPS-025 · REQ-OPS-034 · ADR-0155 · issue #2775 ·
  [`dependency-submission.yml`](../../.github/workflows/dependency-submission.yml) ·
  [`vulnerability-scan.yml`](../../.github/workflows/vulnerability-scan.yml)

## Context

The OWASP Dependency-Check matches dependencies to CVEs through the NVD. It has to hold a local
copy of the whole NVD dataset, and every attempt to keep that copy in CI ran into nvd.nist.gov:

- a scan that downloaded the dataset itself stalled or ran out of time whenever the site was slow,
  and failed pull requests that changed no dependency;
- ADR-0324 moved the download into a daily refresh with an Actions cache. Its first runs on `main`
  failed (the plugin's `Update` task is not configuration-cache compatible), and with no cached
  feed every scan failed, by design, within seconds. Fixing that left the scan as dependent on the
  NVD as before, only one step removed;
- matching by CPE produced most of the findings the project had to suppress: ten of the eleven
  entries in `config/owasp/dependency-check-suppressions.xml` were CPE confusions or advisories for
  another component (OpenTelemetry Go and JS advisories on the Java jars, the LINE messenger on
  pitest, the Eclipse Spring Tools on devtools, the Kotlin toolchain on kotlin-stdlib), or matched
  jars on no runtime classpath.

The OWASP job was never a required check (ADR-0155, correction of 2026-09-22).

## Decision

We will check dependencies against the GitHub Advisory Database, which identifies packages by
Package URL and needs no local dataset, and retire the OWASP Dependency-Check.

1. **A dependency review gates every pull request.** The `Dependency review` job of
   `dependency-submission.yml` runs `actions/dependency-review-action` after the job that submits
   the pull request's resolved Gradle graph (`gradle/actions/dependency-submission`, the pattern the
   Gradle and GitHub documentation give for build-time graphs). It fails when the pull request adds
   a dependency with a HIGH or CRITICAL advisory in any scope, the counterpart of the old
   `failBuildOnCVSS = 7.0`. npm lockfiles are in the graph without a submission. A fork pull
   request, which gets no write token, is reviewed on its manifests alone. The workflow also
   triggers on `merge_group`, where both jobs skip so that the required check reports as passed in
   the merge queue.
2. **Trivy scans the SBOMs.** `vulnerability-scan.yml` runs on every `main` push, weekly, on demand
   and on pull requests that touch the build files, the catalog or a lockfile. It builds the four
   CycloneDX SBOMs from the resolved runtime classpaths (REQ-OPS-025) and runs Trivy over each and
   over the npm lockfiles, dev dependencies included, with the settings of the image scans
   (ADR-0155): `HIGH,CRITICAL`, `ignore-unfixed`. A finding fails that target's job; outside pull
   requests the SARIF goes to the Security tab as `trivy-deps-<target>`. A failed weekly run opens
   the issue *Weekly vulnerability scan failed*. Accepted findings go into
   `.github/trivy/ignore.yaml` with a statement and an expiry.
3. **Dependabot alerts stay the continuous signal on `main`.** They read the same advisory database
   from the submitted graph and report a new advisory between scans.
4. **The OWASP plugin, its two workflows, its suppression file and its NVD caches go.**

Trivy over OSV-Scanner: Trivy is already pinned in this repository and scans every image at
release and at promotion (ADR-0155), so the dependency scan and the image gate share one tool, one
database and one severity rule. OSV-Scanner would add a second scanner with a second ignore format
for the same question.

## Consequences

- No CI job contacts nvd.nist.gov; neither check needs an API key or a cache to give a verdict.
- The PR gate looks at what a pull request **adds**; an advisory published later against a
  dependency already on `main` shows in Dependabot alerts and fails the next scan on `main`.
- None of the OWASP suppressions carries over. Matched by Package URL, the CPE confusions do not
  occur; the compile-only jars (the Guava in NullAway's dataflow, Vert.x, httpcore 4) are not in
  any runtime SBOM; and Trivy reports no advisory for the npm `braces` entry. The ignore file
  starts empty.
- `ignore-unfixed` means an advisory without a fixed version does not fail the scan. It still shows
  in Dependabot alerts.
- The root build loses the plugin and the `jsoup` floor it alone needed. The entries it left in
  `gradle/verification-metadata.xml` stay until the next full regeneration; an unused entry does no
  harm (REQ-OPS-034).
- Making `Dependency review` a required check is a ruleset change for the owner.

## Alternatives considered

- **Keep OWASP with a working NVD refresh.** It fixes the symptom; the scan stays bound to the NVD's
  availability and to CPE matching.
- **OSV-Scanner** for the full scan. Equivalent coverage, but a second tool next to the Trivy the
  image gates already use.
- **Dependency review alone.** It sees only changes, so a vulnerable dependency already on `main`
  would need Dependabot alone to surface it, with no SARIF and no weekly failure.
