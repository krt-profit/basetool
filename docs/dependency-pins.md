# Dependency pins, holds and floors

> **Doc type:** Living reference — kept in sync with `main`. Last reviewed: 2026-09-25.

This file records why a dependency version is **held** behind upstream, **pinned** to match another
component, or **floored** / overridden for a security advisory — the standing reasons the version
catalog and the build scripts no longer carry as comments ([ADR-0214](adr/0214-code-carries-no-comments-besides-javadoc.md)).
A pull request that changes, drops or adds such an entry updates its row here in the same PR; why a
bump was *taken* belongs in that PR's body, not here.

Keys are version keys of [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) unless a build
script is named. "Dependabot #N" is the repository's Dependabot alert number.

## Held behind upstream

| Entry | Value | Reason | Revisit when |
|---|---|---|---|
| `springBoot` | 4.1.1 | The only newer builds are 4.2.0 milestones (M1, M2). Several other rows move with the Boot BOM (Lombok, OpenTelemetry, the Tomcat and Netty overrides, JUnit). | 4.2.0 goes GA. |
| `mapstruct` | 1.6.3 | The only newer builds are 1.7.0 betas (Beta1, Beta2); stay on the 1.6.x stable line. | 1.7.0 final is released. |

## Pinned to match another component

| Entry | Value | Must equal | Reason | Revisit when |
|---|---|---|---|---|
| `openapiGenerator` (plugin `org.openapi.generator`) | 7.25.0 | `basetool-android` `openapiGenerator` | Both generate from the one committed `openapi.json`; a version skew would give two readings of one contract (ADR-0161 §8.2). | Bump both repositories together. |
| `licensee` (plugin `app.cash.licensee`) | 1.14.1 | `basetool-android` `licensee` | Both halves answer the same licence question with the same tool, so a differing verdict can only be version skew (REQ-UI-021, ADR-0197). | Bump both repositories together. |
| `lombok` (keycloak-spi only) | 1.18.46 | Boot-managed Lombok (1.18.46 under Boot 4.1.1) | `lombok.config` is one shared file; two Lombok versions would read it two ways. Every other module takes Lombok from the Boot BOM. Check with `./gradlew :backend:dependencyInsight --configuration compileClasspath --dependency org.projectlombok:lombok`. | Move together with the Boot bump that moves the managed version. |
| `keycloak` (keycloak-spi `compileOnly` SPI artifacts) | 26.7.4 | The Keycloak runtime image `quay.io/keycloak/keycloak:26.7` | Compile against the highest 26.7.x patch, the one the `26.7` line resolves to; SPIs are stable within a minor, and an exact match avoids load-time surprises. | A new 26.7.x patch, or the image moves to another minor. |
| keycloak-spi `options.release` (`keycloak-spi/build.gradle.kts`) | 21 | The JDK in the Keycloak 26.7 image | The provider JAR is loaded by Keycloak's JDK 21; Java 25 bytecode fails with `UnsupportedClassVersionError`. Compiled with the JDK 25 toolchain, emitted as `--release 21`. | Keycloak's image moves to a newer JDK. |
| `checkstyle` | 14.1.0 | `config/checkstyle/google_checks.xml` | That file is an unmodified copy from the `checkstyle-14.1.0` release tag. An older config on a newer tool silently skips every rule added since. | Re-copy the file from the matching release tag in the commit that bumps `checkstyle`. |
| Spring Session licence override (`ossLicenseCoordinateOverrides`, root `build.gradle.kts`) | 4.1.1 | Boot-managed Spring Session | Spring Session's POM names a "Broadcom Foundation License" with no URL; the jars' `META-INF/LICENSE.txt` is Apache-2.0. Licensee allows by full coordinate, so the version is named. | A Boot bump moves Spring Session: `:frontend:licensee` fails naming the new version; re-check the jar's `LICENSE.txt` and move the version. |

## Security floors and overrides

| Entry | Value | Reason | Advisories | Drop when |
|---|---|---|---|---|
| `tomcat.version` Boot BOM property (root `build.gradle.kts`, `plugins.withId("org.springframework.boot")`) | 11.0.25 | Boot 4.1.1 manages 11.0.24. Embedded Tomcat ships in backend, frontend and ingest, so it is upgraded, not suppressed. 11.0.25 stays on the 11.0 line the Boot 4.1 BOM expects. Set as a BOM property in the root script: in `gradle.properties` it is silently overridden, and a plain constraint collides with the BOM's `strictly` constraints. | CVE-2026-65637, CVE-2026-65905, CVE-2026-65182, CVE-2026-68525, CVE-2026-65183, CVE-2026-66422, CVE-2026-68569, CVE-2026-65927, CVE-2026-68763, CVE-2026-73180, CVE-2026-66299 (Tomcat 11.0.25 advisory, 2026-08-25) | The Boot BOM manages ≥ 11.0.25. |
| `netty.version` Boot BOM property (root `build.gradle.kts`) | 4.2.18.Final | Boot 4.1.1 manages 4.2.17.Final, affected by HTTP request smuggling via an unvalidated final transfer coding (4.2.13–4.2.17). Netty ships in all three apps (Reactor Netty under WebClient); the property moves the whole `netty-bom` family. Same mechanism as Tomcat. | CVE-2026-89044, GHSA-hcvj-94mj-jp5c | The Boot BOM manages ≥ 4.2.18.Final. |
| `springdocOpenapi` | 3.1.1 (minimum) | Security release with eight advisories; two reach this deployment: the per-locale OpenAPI cache grew without bound through `Accept-Language` (now capped by `springdoc.cache.max-entries`), and a ThreadLocal leaked headers between concurrent requests. | — (not named individually) | Never go below 3.1.1. |
| `netty41` (keycloak-spi, `platform(libs.netty41.bom)` on `compileOnly` and test) | 4.1.138.Final | Keycloak 26.7.4 → Quarkus 3.33.3.2 brings netty 4.1.136.Final onto the SPI's compile and test classpaths. A BOM floor, not a strict pin, so a Keycloak bump bringing a newer 4.1.x wins. Nothing ships: the provider JAR bundles no dependencies and Keycloak runs its own netty; the floor keeps the dependency-check scan pointed at open CVEs. | CVE-2026-75595, CVE-2026-75596 (fixed 4.1.137.Final), CVE-2026-89044 (fixed 4.1.138.Final) | `keycloak` resolves netty ≥ 4.1.138.Final on its own (Quarkus 3.33.3.3 manages it; no Keycloak release used it as of 2026-09-22). |
| `protobuf3` (keycloak-spi, `platform(libs.protobuf3.bom)`) | 3.25.9 | Keycloak 26.7.4 → Quarkus 3.33.3.2 → vertx-grpc → grpc-protobuf 1.65.0 brings protobuf-java 3.25.1. 3.25.9 is the newest patch on the 3.25 line grpc 1.65 was built for; 4.x leaves that line. Constraints only, nothing reaches the provider JAR. | CVE-2024-7254, Dependabot #12 | `keycloak` resolves protobuf-java ≥ 3.25.5 on its own. |
| `opentelemetry` (keycloak-spi, `platform(libs.opentelemetry.bom)`) | 1.62.0 | Keycloak 26.7.4 brings the 1.57.0 family. 1.62.0 is also what the Boot 4.1.1 BOM puts on backend, frontend and ingest, so the build resolves one `opentelemetry-api`. The `-alpha` artifacts are not in this BOM and stay where Keycloak puts them. | CVE-2026-45292, Dependabot #14 | `keycloak` resolves ≥ 1.62.0 on its own. Until then, move it only together with the Boot bump that moves the managed version. |
| `handlebars` (frontend `buildscript` constraint) | 4.5.5 | openapi-generator 7.25.0 pins handlebars 4.3.1 and upstream still does, so no plugin bump fixes it. Build-time only; generation uses the default Mustache engine. `handlebars-jackson2` stays at 4.3.1 (its last release, no advisory). | CVE-2026-55760, Dependabot #15 | `openapiGenerator` brings handlebars ≥ 4.5.2 itself. |
| `plexusUtils` (root `buildscript` constraint) | 3.6.1 | licensee 1.14.1 → maven-model-builder 3.9.11 pins plexus-utils 3.6.0. 3.6.1 is what the CycloneDX plugin on the same root classpath asks for, and subproject classloaders delegate to the root first. Build-time only. Do not take 4.x: it moved the `org.codehaus.plexus.util.xml` classes maven-model 3.9 needs into `plexus-xml` (`NoClassDefFoundError` in the Android app on 2026-09-04). | CVE-2025-67030, Dependabot #13 | `licensee` brings plexus-utils ≥ 3.6.1 itself. |
| `commonsLang3` (`buildscript` constraint in backend and ingest) | 3.20.0 | Spring Boot plugin → spring-boot-buildpack-platform → commons-compress 1.27.1 asks for 3.16.0. Never loaded at run time (the root classpath carries 3.20.0 and is asked first); the constraint makes the reported graph agree with what runs. 3.20.0 is what every other build classpath resolves. | CVE-2025-48924, GHSA-j288-q9x7-2f5v, Dependabot #19 | `springBoot` brings commons-lang3 ≥ 3.18.0 itself (commons-compress 1.28.0 does). |

A floor only has to clear its advisory: stay on the lines above rather than taking a new major.

## Module-specific and tooling pins

| Entry | Value | Reason | Revisit when |
|---|---|---|---|
| `junit` (keycloak-spi only) | 6.1.3 | keycloak-spi is deliberately no Spring Boot module and has no BOM to supply JUnit. Boot 4.1.1 manages 6.0.3, so the repository runs two JUnit lines; harmless, as keycloak-spi shares no classpath with the others. | Restore the lockstep, or drop the pin, once the Boot BOM moves to 6.1.x. |
| `mockito` (keycloak-spi only) | 5.24.0 | Same reason as `junit`; backend, frontend and ingest keep Boot's managed Mockito. Kept in the catalog, not the build script, so refreshVersions can see it. The module's tests set `net.bytebuddy.experimental=true` because Byte Buddy does not yet officially support the JDK 25 toolchain. | Drop the Byte Buddy flag once Mockito's Byte Buddy officially supports JDK 25. |
| `test-support`, `logging-support` | Boot BOM | Both import the Spring Boot BOM without the Boot plugin and pin nothing of their own, so they compile against what the applications run. No `platform(libs.junit.bom)` on top (removed 2026-09-22: it put a second JUnit line into one module). | — |
| `googleJavaFormat` | 1.36.1 (≥ 1.35.0) | Older google-java-format reflects on a javac method whose return type changed in JDK 25 and fails every file with `NoSuchMethodError`. | Keep ≥ 1.35.0. |
| `node` | 24.21.0 | The Node.js runtime the node-gradle plugin downloads; not a Maven artifact, so refreshVersions cannot track it. Regenerate the `win-x64` and `linux-x64` verification entries on a bump (see CONTRIBUTING → *Dependency verification*). | Bump by hand against the active Node LTS line. |
| refreshVersions plugin (`settings.gradle.kts`) | 0.60.6 | Declared `apply false` so it still resolves, stays pinned and stays in the dependency verification metadata; applied only with `-PrefreshVersions` because it is not configuration-cache compatible. | Bump by hand. |

## Deliberate exclusions

| Where | Excluded | Reason | Revisit when |
|---|---|---|---|
| backend `spring-boot-starter-data-jpa` | `org.springframework:spring-aspects`, `org.aspectj:aspectjweaver` | Licence, not size (ADR-0197): the AspectJ jar is `EPL-2.0 AND BSD-3-Clause AND Apache-1.1` with no GPL secondary licence and may not ship in a GPL-3.0-only image. Nothing uses AspectJ; the proxy-based advisors (`@Transactional`, `@PreAuthorize`, `@Cacheable`, `@Async`, `@Scheduled`) work without it. `:backend:licensee` refuses EPL-2.0 if a new path brings it back. | Never, while the images are GPL-3.0-only. |
| backend, frontend, ingest `spring-boot-starter-opentelemetry` | `io.micrometer:micrometer-registry-otlp` | It would switch on Boot's OTLP metrics push to a localhost default endpoint in every environment. Metrics are Prometheus pull only (REQ-OBS-005). | Metrics export moves to OTLP. |

## Proposals refused on the 2026-09-25 sweep

These refreshVersions proposals were open in the catalog when the comments were removed. Refusing one
again on a later sweep needs no new row; a changed reason does.

| Entry | Refused proposals | Reason |
|---|---|---|
| `springBoot` | 4.2.0-M1, 4.2.0-M2 | Milestones only. |
| `mapstruct` | 1.7.0.Beta1, 1.7.0.Beta2 | Betas only. |
| `lombok` | 1.18.48 | Equality with the Boot-managed version (still 1.18.46 under Boot 4.1.1, re-verified 2026-09-21 and 2026-09-25). |
| `netty41` | 4.2.0.Alpha1 … 4.2.18.Final | The floor is for Keycloak's 4.1 line (Quarkus 3.33.3.2 and vertx-core 4.5.31 still ask for 4.1.136.Final); a 4.2 BOM would make the scan describe a netty Keycloak never runs. No newer 4.1.x was proposed. |
| `protobuf3` | 4.0.0-rc-1 … 4.36.2 | grpc-protobuf is still 1.65.0, built for the 3.x line; 3.25.9 is still the newest 3.25 patch. |
| `opentelemetry` | 1.63.0 … 1.66.0 | The Boot 4.1.1 BOM still selects 1.62.0 on the backend runtime classpath; taking a newer one would split the build into two versions for no advisory. |
