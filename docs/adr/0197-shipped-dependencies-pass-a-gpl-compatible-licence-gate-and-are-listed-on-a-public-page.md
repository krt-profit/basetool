# ADR-0197 — Shipped dependencies pass a GPL-compatible licence gate and are listed on a public page

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** @greluc
- **Related:** ADR-0159 (members-only; the public surface is a list) ·
  [`docs/specs/ui-design-system.md`](../specs/ui-design-system.md) (`REQ-UI-021`) ·
  [`docs/specs/security-and-access.md`](../specs/security-and-access.md) (`REQ-SEC-052`) ·
  root `build.gradle.kts` (the licence policy tables) · `frontend/oss-bundled-components.json` ·
  the Android app's `REQ-APP-SET-006`, which answers the same question for the APK

## Context

The Basetool is **GPL-3.0-only**, and its container images are published to GHCR. Publishing an
image is distribution, so two duties apply to everything inside it, not just to our own code:

1. **Compatibility.** Every third-party library linked into a module has to be one we may
   redistribute as part of a GPL-3.0-only work.
2. **Notice.** Most licences (Apache-2.0 §4, MIT, BSD, OFL-1.1 §2) require that recipients are told
   what they received and under which licence.

Nothing checked either. CycloneDX SBOMs exist per module, but they are generated only at release,
nobody reads their licence field, and there is no allow-list. The Android app has had both halves
since `REQ-APP-SET-006` — a Licensee gate with an allow-list and an in-app „Open-Source-Lizenzen“
screen — and the web side had neither.

An audit of all four shipped modules on 2026-09-22 (246 runtime artifacts) found **one real
incompatibility**: `org.aspectj:aspectjweaver`, pulled into the backend by
`spring-boot-starter-data-jpa` → `spring-aspects`. Its own `LICENSE-AspectJ.adoc` declares
`EPL-2.0 AND BSD-3-Clause AND Apache-1.1`: EPL-2.0 with **no** GPL Secondary License designated,
plus a modified BCEL 5.1 under Apache-1.1. Both are GPL-incompatible. Everything else was
compatible — directly, or through one alternative of a dual licence (logback: EPL-2.0 **or**
LGPL-2.1; the Jakarta APIs: EPL-2.0 **or** GPL-2.0 with Classpath Exception **or** EDL). Two
further findings were metadata, not licence: Spring Session 4.1.1's POM names a "Broadcom
Foundation License" with no URL while its jar's `LICENSE.txt` is Apache-2.0, and a dozen POMs point
at licence URLs Licensee cannot map to an SPDX identifier (EDL, the Resilience4j and Flyway repos,
…). The bundled Lato font shipped without its OFL text in three of its four copies.

## Decision

1. **Every shipped JVM module runs Licensee** (`app.cash.licensee`, the same version as the Android
   app) against its `runtimeClasspath`, wired into `check`. One policy, in the root
   `build.gradle.kts`, applies to all four:
   - `ossAllowedLicenses` — the SPDX identifiers that may ship: GPL-3.0-compatible **and** present.
     `EPL-2.0` on its own is deliberately absent; a dual-licensed POM needs only one allowed
     alternative, which is Maven's and Licensee's reading of a licence list.
   - `ossLicenseUrlAliases` — POM licence URLs mapped to the SPDX identifier their text is. The gate
     allows a URL whose identifier is allowed; the page files the component under that identifier.
   - `ossLicenseCoordinateOverrides` — coordinates whose POM names no usable licence (Spring
     Session), pinned by version so a bump forces a re-read of the jar's licence file.
2. **AspectJ is excluded, not allowed.** `spring-aspects` and `aspectjweaver` are excluded from the
   backend's data-JPA starter. Nothing needs them: no `@Aspect`, `@Configurable`, `@Timed`,
   `@Observed`, no load-time weaving; `@Transactional`, `@PreAuthorize`, `@Cacheable`, `@Async`
   and `@Scheduled` are proxy-based infrastructure advisors that Spring Boot keeps applying
   without AspectJ. The full backend suite is the regression check. Because `EPL-2.0` is not
   allowed, any path that brings AspectJ back fails `:backend:licensee`.
3. **A public page lists what ships** — `/licenses`, „Open-Source-Lizenzen", linked in the footer
   beside the Nutzungsbedingungen, in the sidebar's legal group and on the landing page
   (`REQ-UI-021`). `:frontend:generateOssLicenses` merges the four Licensee reports (read through
   a consumable `ossLicenseReportElements` variant, not through another module's build directory)
   with the hand-kept `frontend/oss-bundled-components.json` (Lato; the Temurin JRE and the Alpine
   layer of the images) into a classpath resource. It is generated on every build and never
   committed, so the page names the exact versions of the build serving it.
4. **The page is public**, and `REQ-SEC-052` grows by one entry: the notice is owed to whoever
   receives the software, the landing page already serves the bundled font to an anonymous
   visitor, and the page makes no backend call and carries no data. It is also a legal page for
   the session gates (`PublicPaths.LEGAL_PAGES`): a member held at the consent page can still read
   it.
5. **Font licence texts ship beside the fonts.** `OFL.txt` sits next to every Lato copy (frontend,
   backend PDF fonts, both Keycloak theme folders); `OssBundledComponentsTest` fails on a font
   directory without it and on a font family missing from the bundled list.

## Alternatives rejected

- **Allow AspectJ, or add a GPL §7 additional permission for EPL/Apache-1.1 libraries.** The
  permission would have been legally sound (the CLA grants the sublicensing right), but it widens
  our own licence to cover a library nothing uses. Excluding it costs one `exclude` and keeps the
  licence text untouched.
- **Generate the page from the CycloneDX SBOMs.** They are release-only by design (root build) and
  would either force SBOM generation into every build or show a page one release behind. The SBOM
  has no allow-list either, so the gate would still be missing.
- **`com.github.jk1.dependency-license-report`.** Capable, but a second licence tool beside the
  Android app's Licensee would answer the same question two ways.
- **A hand-maintained list.** Drifts with the first dependency bump — exactly the failure the
  Android screen was built to avoid.
- **Members-only page.** The anonymous visitor on the landing page receives the font too, and a
  footer link that bounces into the login reads as broken.

## Consequences

- A dependency under a licence that is not on the list fails the build of the module that would
  ship it. Adding a licence to the list is a deliberate, reviewed change to the root build script.
- A Spring Boot bump that moves Spring Session fails `:frontend:licensee` until the pinned version
  in `ossLicenseCoordinateOverrides` moves — by design, after re-reading the jar's `LICENSE.txt`.
- The frontend build now resolves the backend's, ingest's and keycloak-spi's runtime graphs (their
  `licensee` tasks run before `processResources`). No compile dependency is created.
- The images still contain GPL-2.0 packages from the Alpine base layer; the page names the layer
  and links Alpine's package index and aports, where each package's licence and source are
  published. Whether that satisfies GPL-2.0 §3 for a redistributed binary layer or a written offer
  is also needed has **not** been assessed by anyone qualified — the same open item the Android
  app records for bundled licence texts.
- Kept out of scope: the images run beside unmodified upstream services (Keycloak, PostgreSQL,
  Redis, the monitoring stack). They are separate programs we do not build or ship, so they are not
  listed.
