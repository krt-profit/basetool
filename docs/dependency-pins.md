# Dependency pins, holds and floors

> **Doc type:** Living reference — kept in sync with `main`. Last reviewed: 2026-10-04.

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
| `keycloak` (keycloak-spi `compileOnly` SPI artifacts) | 26.8.0 | The Keycloak runtime image `quay.io/keycloak/keycloak:26.8` | Compile against the highest 26.8.x patch, the one the `26.8` line resolves to; SPIs are stable within a minor, and an exact match avoids load-time surprises. `scripts/check-keycloak-version.py` (`repo-lint.yml`, REQ-OPS-040) fails when the catalog and the three image pins name different minors. | A new 26.8.x patch, or the image moves to another minor. |
| keycloak-spi `options.release` (`keycloak-spi/build.gradle.kts`) | 21 | The JDK in the Keycloak 26.8 image (OpenJDK 21.0.12, read from the pinned image on 2026-10-01) | The provider JAR is loaded by Keycloak's JDK 21; Java 25 bytecode fails with `UnsupportedClassVersionError`. Compiled with the JDK 25 toolchain, emitted as `--release 21`. | Keycloak's image moves to a newer JDK. |
| `checkstyle` | 14.3.0 | `config/checkstyle/google_checks.xml` | That file is the `google_checks.xml` of the `checkstyle-14.3.0` release tag with exactly two edits: its XML comments are removed (ADR-0214), and `EmptyCatchBlock` takes `exceptionVariableName` `^(ignored\|expected\|_)$` instead of `commentFormat`, because an empty `catch` names its variable rather than carrying a comment (`_` since ADR-0214's D-14 amendment). An older config on a newer tool silently skips every rule added since. | Re-copy the file from the matching release tag in the commit that bumps `checkstyle`, then re-apply the two edits; nothing else may differ from upstream. |
| Spring Session licence override (`ossLicenseCoordinateOverrides`, root `build.gradle.kts`) | 4.1.1 | Boot-managed Spring Session | Spring Session's POM names a "Broadcom Foundation License" with no URL; the jars' `META-INF/LICENSE.txt` is Apache-2.0. Licensee allows by full coordinate, so the version is named. | A Boot bump moves Spring Session: `:frontend:licensee` fails naming the new version; re-check the jar's `LICENSE.txt` and move the version. |

## Security floors and overrides

| Entry | Value | Reason | Advisories | Drop when |
|---|---|---|---|---|
| `tomcat.version` Boot BOM property (root `build.gradle.kts`, `plugins.withId("io.spring.dependency-management")`) | 11.0.25 | Boot 4.1.1 manages 11.0.24. Embedded Tomcat ships in backend, frontend and ingest, so it is upgraded, not suppressed. 11.0.25 stays on the 11.0 line the Boot 4.1 BOM expects. Set as a BOM property in the root script: in `gradle.properties` it is silently overridden, and a plain constraint collides with the BOM's `strictly` constraints. | CVE-2026-65637, CVE-2026-65905, CVE-2026-65182, CVE-2026-68525, CVE-2026-65183, CVE-2026-66422, CVE-2026-68569, CVE-2026-65927, CVE-2026-68763, CVE-2026-73180, CVE-2026-66299 (Tomcat 11.0.25 advisory, 2026-08-25) | The Boot BOM manages ≥ 11.0.25. |
| `netty.version` Boot BOM property (root `build.gradle.kts`) | 4.2.18.Final | Boot 4.1.1 manages 4.2.17.Final, affected by HTTP request smuggling via an unvalidated final transfer coding (4.2.13–4.2.17). Netty ships in all three apps (Reactor Netty under WebClient); the property moves the whole `netty-bom` family. Same mechanism as Tomcat. | CVE-2026-89044, GHSA-hcvj-94mj-jp5c | The Boot BOM manages ≥ 4.2.18.Final. |
| `jackson-2-bom.version` and `jackson-bom.version` Boot BOM properties (root `build.gradle.kts`, values from `jackson2` and `jackson3`) | 2.22.3 / 3.2.3 | Boot 4.1.1 manages 2.21.5 and 3.1.5 (3.2.x is a newer minor than the 3.1 line Boot 4.1 builds on; taken with the full test suite green). Jackson 3 ships in all three apps, Jackson 2 in backend and frontend. Like every BOM property here it is set where the dependency-management plugin is applied, so `logging-support` (logstash-logback-encoder → Jackson 3) and `test-support` get it too; set for Boot-plugin modules only, it would leave `logging-support` on 3.1.5. Same mechanism as Tomcat. | CVE-2026-68497 (GHSA-q4xh-88c3-wmh7), CVE-2026-19032 (GHSA-wjgm-6hv5-3cvf), CVE-2026-83557 (GHSA-gx83-3vf8-gh7j); Dependabot #20, #23, #26; CVE-2026-91776 (GHSA-wv8q-qhhj-9h54), CVE-2026-91777 (GHSA-cxp5-3px4-pw24); Dependabot #33, #35, #36, #37; jackson-core CVE-2026-89425 (GHSA-7hhh-6rmp-j9qf), CVE-2026-89407 (GHSA-p6pp-m3f8-5c89), fixed in 2.21.7 and 3.1.7 | The Boot BOM manages ≥ 2.22.3 and ≥ 3.2.3. |
| `springdocOpenapi` | 3.1.1 (minimum) | Security release with eight advisories; two reach this deployment: the per-locale OpenAPI cache grew without bound through `Accept-Language` (now capped by `springdoc.cache.max-entries`), and a ThreadLocal leaked headers between concurrent requests. | — (not named individually) | Never go below 3.1.1. |
| `protobuf3` (keycloak-spi, `platform(libs.protobuf3.bom)`) | 3.25.9 | Keycloak 26.7.4 and 26.8.0 → Quarkus → vertx-grpc → grpc-protobuf 1.65.0 brings protobuf-java 3.25.1. 3.25.9 is the newest patch on the 3.25 line grpc 1.65 was built for; 4.x leaves that line. Constraints only, nothing reaches the provider JAR. | CVE-2024-7254, Dependabot #12 The 26.8.0 image itself ships protobuf-java 4.35.0, but the Maven graph the SPI compiles against still resolves 3.25.1 through vertx-grpc, which is what the scan reads (checked 2026-10-01: dropping the floor made the OWASP scan report CVE-2024-7254 again). | `keycloak` resolves protobuf-java ≥ 3.25.5 on its own. |
| `jackson2Plugins` (root and frontend `buildscript` constraint on jackson-databind) | 2.22.3 | The OWASP dependency-check plugin 13.0.0 (open-vulnerability-clients 9.0.6 imports jackson-bom 2.22.1) and CycloneDX 3.4.1 (cyclonedx-core-java 13.1.0) bring 2.22.1 onto the root classpath; node-gradle 7.1.0 and openapi-generator 7.25.0 resolve the same on the frontend's. databind 2.22.3 aligns the rest of the family through its BOM. Build-time only. Stays on the 2.22 line these plugins ask for. | CVE-2026-68497, CVE-2026-19032, CVE-2026-83557; Dependabot #22, #25, #28; CVE-2026-91776, CVE-2026-91777; Dependabot #34, #38; CVE-2026-89425, CVE-2026-89407 (jackson-core 2.22.3) | These plugins bring jackson-databind ≥ 2.22.3 themselves. |
| `jackson3` (`buildscript` constraint in backend, frontend and ingest) | 3.2.3 | Spring Boot plugin → spring-boot-buildpack-platform 4.1.1 → tools.jackson 3.1.5. Build-time only: the buildpack code runs for `bootBuildImage`, which nothing calls (the images are built from Dockerfiles). Also the value of `jackson-bom.version`. | CVE-2026-68497, CVE-2026-19032, CVE-2026-83557; Dependabot #20, #23, #26; CVE-2026-91776, CVE-2026-91777; Dependabot #35, #37; CVE-2026-89425, CVE-2026-89407 (jackson-core 3.1.7) | `springBoot` brings tools.jackson ≥ 3.2.3 itself, with the Boot bump that drops `jackson-bom.version`. |
| `handlebars` (frontend `buildscript` constraint) | 4.5.5 | openapi-generator 7.25.0 pins handlebars 4.3.1 and upstream still does, so no plugin bump fixes it. Build-time only; generation uses the default Mustache engine. `handlebars-jackson2` stays at 4.3.1 (its last release, no advisory). | CVE-2026-55760, Dependabot #15 | `openapiGenerator` brings handlebars ≥ 4.5.2 itself. |
| `plexusUtils` (root `buildscript` constraint) | 3.6.1 | licensee 1.14.1 → maven-model-builder 3.9.11 pins plexus-utils 3.6.0. 3.6.1 is what the CycloneDX plugin on the same root classpath asks for, and subproject classloaders delegate to the root first. Build-time only. Do not take 4.x: it moved the `org.codehaus.plexus.util.xml` classes maven-model 3.9 needs into `plexus-xml` (`NoClassDefFoundError` in the Android app on 2026-09-04). | CVE-2025-67030, Dependabot #13 | `licensee` brings plexus-utils ≥ 3.6.1 itself. |
| `commonsLang3` (`buildscript` constraint in backend and ingest) | 3.21.0 | Spring Boot plugin → spring-boot-buildpack-platform → commons-compress 1.27.1 asks for 3.16.0. Never loaded at run time (the root classpath carries 3.21.0 and is asked first); the constraint makes the reported graph agree with what runs. 3.21.0 is what every other build classpath resolves. | CVE-2025-48924, GHSA-j288-q9x7-2f5v, Dependabot #19 | `springBoot` brings commons-lang3 ≥ 3.18.0 itself (commons-compress 1.28.0 does). |

A floor only has to clear its advisory: stay on the lines above rather than taking a new major.

## Module-specific and tooling pins

| Entry | Value | Reason | Revisit when |
|---|---|---|---|
| `junit` (keycloak-spi only) | 6.1.3 | keycloak-spi is deliberately no Spring Boot module and has no BOM to supply JUnit. Boot 4.1.1 manages 6.0.3, so the repository runs two JUnit lines; harmless, as keycloak-spi shares no classpath with the others. | Restore the lockstep, or drop the pin, once the Boot BOM moves to 6.1.x. |
| `mockito` (keycloak-spi only) | 5.24.0 | Same reason as `junit`; backend, frontend and ingest keep Boot's managed Mockito. Kept in the catalog, not the build script, so refreshVersions can see it. The module's tests set `net.bytebuddy.experimental=true` because Byte Buddy does not yet officially support the JDK 25 toolchain. | Drop the Byte Buddy flag once Mockito's Byte Buddy officially supports JDK 25. |
| `test-support`, `logging-support` | Boot BOM | Both import the Spring Boot BOM without the Boot plugin and pin nothing of their own; the root script's BOM property overrides reach them through the dependency-management plugin, so they compile against what the applications run. No `platform(libs.junit.bom)` on top (removed 2026-09-22: it put a second JUnit line into one module). | — |
| `googleJavaFormat` | 1.36.1 (≥ 1.35.0) | Older google-java-format reflects on a javac method whose return type changed in JDK 25 and fails every file with `NoSuchMethodError`. | Keep ≥ 1.35.0. |
| `node` | 24.21.0 | The Node.js runtime the node-gradle plugin downloads; not a Maven artifact, so refreshVersions cannot track it. Regenerate the `win-x64` and `linux-x64` verification entries on a bump (see CONTRIBUTING → *Dependency verification*). | Bump by hand against the active Node LTS line. |
| pitest `addJUnitPlatformLauncher` (root `build.gradle.kts`, `plugins.withId("info.solidsoft.pitest")`) | `false` | The plugin's launcher probe resolves a detached copy of `testImplementation` without the Boot BOM and its overrides. Nothing from it reaches a classpath, but the dependency-submission graph records it: in the backend it resolved tools.jackson 3.2.1 (json-schema-validator 3.0.7 asks for it), which raised Dependabot #40–#46 although every real configuration resolves 3.1.7. The pitest classpath includes the test runtime classpath, which already carries the Boot-managed `junit-platform-launcher` (declared `testRuntimeOnly` in backend and ingest, through `spring-boot-starter-test` in the frontend). | A pitest module's test runtime classpath loses the launcher. |
| refreshVersions plugin (`settings.gradle.kts`) | 0.60.6 | Declared `apply false` so it still resolves, stays pinned and stays in the dependency verification metadata; applied only with `-PrefreshVersions` because it is not configuration-cache compatible. | Bump by hand. |

## Deliberate exclusions

| Where | Excluded | Reason | Revisit when |
|---|---|---|---|
| backend `spring-boot-starter-data-jpa` | `org.springframework:spring-aspects`, `org.aspectj:aspectjweaver` | Licence, not size (ADR-0197): the AspectJ jar is `EPL-2.0 AND BSD-3-Clause AND Apache-1.1` with no GPL secondary licence and may not ship in a GPL-3.0-only image. Nothing uses AspectJ; the proxy-based advisors (`@Transactional`, `@PreAuthorize`, `@Cacheable`, `@Async`, `@Scheduled`) work without it. `:backend:licensee` refuses EPL-2.0 if a new path brings it back. | Never, while the images are GPL-3.0-only. |
| backend, frontend, ingest `spring-boot-starter-opentelemetry` | `io.micrometer:micrometer-registry-otlp` | It would switch on Boot's OTLP metrics push to a localhost default endpoint in every environment. Metrics are Prometheus pull only (REQ-OBS-005). | Metrics export moves to OTLP. |

## Proposals refused on the 2026-09-28 sweep

These refreshVersions proposals were first refused on the 2026-09-25 sweep, when the comments were
removed, and again unchanged on the 2026-09-28 sweep (#2248), with every reason re-checked. Refusing
one again on a later sweep needs no new row; a changed reason does.

The 2026-09-28 sweep took the two build-tooling patches it proposed: `spotbugs` (the
`com.github.spotbugs-base` plugin) 6.5.11 → 6.5.12 and `spotless` 8.10.2 → 8.10.3. Both are Gradle
plugins only, so neither reaches a runtime classpath or an image.

| Entry | Refused proposals | Reason |
|---|---|---|
| `springBoot` | 4.2.0-M1, 4.2.0-M2 | Milestones only. |
| `mapstruct` | 1.7.0.Beta1, 1.7.0.Beta2 | Betas only. |
| `springModulith` | 2.2.0-M1, 2.2.0-M2 | Milestones only; test scope plus the `compileOnly` `spring-modulith-api` (REQ-MOD-006). |
| `lombok` | 1.18.48 | Equality with the Boot-managed version (still 1.18.46 under Boot 4.1.1, re-verified 2026-09-21, 2026-09-25, 2026-09-28, 2026-10-04 and 2026-10-09). |
| `googleJavaFormat` | 1.37.0 | Spotless 8.10.3 (the newest release) cannot drive it: every file fails with `InvocationTargetException` in `spotlessJavaCheck` (2026-10-04). Stay on 1.36.1. |
| `protobuf3` | 4.0.0-rc-1 … 4.36.2 | grpc-protobuf is still 1.65.0, built for the 3.x line; 3.25.9 is still the newest 3.25 patch. |
