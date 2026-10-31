# Dependency pins, holds and floors

> **Doc type:** Living reference — kept in sync with `main`. Last reviewed: 2026-10-31.

This file records why a dependency version is **held** behind upstream, **pinned** to match another
component, or **floored** / overridden for a security advisory — the standing reasons the version
catalog and the build scripts do not carry as comments ([ADR-0214](adr/0214-code-carries-no-comments-besides-javadoc.md)).
A pull request that changes, drops or adds such an entry updates its row here in the same PR; why a
bump was *taken* belongs in that PR's body, not here.

Keys are version keys of [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) unless a build
script is named. "Dependabot #N" is the repository's Dependabot alert number.

## Held behind upstream

| Entry | Value | Reason | Revisit when |
|---|---|---|---|
| `springBoot` | 4.1.2 | The only newer builds are 4.2.0 pre-releases. Several other rows move with the Boot BOM (Lombok, OpenTelemetry, the Jackson overrides, JUnit). | 4.2.0 goes GA. |
| `plexusUtils` | 3.6.1 | 4.x moved `org.codehaus.plexus.util.xml` into `plexus-xml`; licensee's maven-model-builder 3.9 still needs it there (`NoClassDefFoundError: org/codehaus/plexus/util/xml/pull/XmlPullParserException` from `:backend:licensee` and `:frontend:licensee` with 4.1.0, 2026-10-31). Also a security floor, see below. | `licensee` moves to Maven 4's model builder. |
| `mapstruct` | 1.6.3 | The only newer builds are 1.7.0 betas (Beta1, Beta2); stay on the 1.6.x stable line. | 1.7.0 final is released. |

## Pinned to match another component

| Entry | Value | Must equal | Reason | Revisit when |
|---|---|---|---|---|
| `lombok` (keycloak-spi only) | 1.18.48 | Boot-managed Lombok (1.18.48 under Boot 4.1.2) | `lombok.config` is one shared file; two Lombok versions would read it two ways. Every other module takes Lombok from the Boot BOM. Check with `./gradlew :backend:dependencyInsight --configuration compileClasspath --dependency org.projectlombok:lombok`. | Move together with the Boot bump that moves the managed version. |
| `keycloak` (keycloak-spi `compileOnly` SPI artifacts) | 26.8.1 | The Keycloak runtime image `quay.io/keycloak/keycloak:26.8` | Compile against the highest 26.8.x patch, the one the `26.8` line resolves to; SPIs are stable within a minor, and an exact match avoids load-time surprises. `scripts/check-keycloak-version.py` (`repo-lint.yml`, REQ-OPS-040) fails when the catalog and the three image pins name different minors. | A new 26.8.x patch, or the image moves to another minor. |
| keycloak-spi `options.release` (`basetool { javaRelease = 21 }` in `keycloak-spi/build.gradle.kts`) | 21 | The JDK in the Keycloak 26.8 image (OpenJDK 21.0.12, read from the pinned 26.8.1 image on 2026-10-19) | The provider JAR is loaded by Keycloak's JDK 21; Java 25 bytecode fails with `UnsupportedClassVersionError`. Compiled with the JDK 25 toolchain, emitted as `--release 21`. | Keycloak's image moves to a newer JDK. |
| `checkstyle` | 14.3.0 | `config/checkstyle/google_checks.xml` | That file is the `google_checks.xml` of the `checkstyle-14.3.0` release tag with exactly two edits: its XML comments are removed (ADR-0214), and `EmptyCatchBlock` takes `exceptionVariableName` `^(ignored\|expected\|_)$` instead of `commentFormat`, because an empty `catch` names its variable rather than carrying a comment (`_` since ADR-0214's D-14 amendment). An older config on a newer tool silently skips every rule added since. | Re-copy the file from the matching release tag in the commit that bumps `checkstyle`, then re-apply the two edits; nothing else may differ from upstream. |
| Spring Session licence override (`OssLicensePolicy.coordinateOverrides`, `build-logic`) | 4.1.1 | Boot-managed Spring Session | Spring Session's POM names a "Broadcom Foundation License" with no URL; the jars' `META-INF/LICENSE.txt` is Apache-2.0. Licensee allows by full coordinate, so the version is named. | A Boot bump moves Spring Session: `:frontend:licensee` fails naming the new version; re-check the jar's `LICENSE.txt` and move the version. |

## Security floors and overrides

| Entry | Value | Reason | Advisories | Drop when |
|---|---|---|---|---|
| `jackson-2-bom.version` and `jackson-bom.version` Boot BOM properties (`build-logic`, `basetool.dependency-management-conventions`, values from `jackson2` and `jackson3`) | 2.22.4 / 3.2.4 | Boot 4.1.2 manages 2.21.7 and 3.1.7 (3.2.x is a newer minor than the 3.1 line Boot 4.1 builds on; taken with the full test suite green). Jackson 3 ships in all three apps, Jackson 2 in backend and frontend. Like every BOM property here it is set where the dependency-management plugin is applied, so `logging-support` (logstash-logback-encoder → Jackson 3) and `test-support` get it too; set for Boot-plugin modules only, it would leave `logging-support` on 3.1.7. Set as a BOM property: in `gradle.properties` it is silently overridden, and a plain constraint collides with the BOM's `strictly` constraints. | CVE-2026-68497 (GHSA-q4xh-88c3-wmh7), CVE-2026-19032 (GHSA-wjgm-6hv5-3cvf), CVE-2026-83557 (GHSA-gx83-3vf8-gh7j); Dependabot #20, #23, #26; CVE-2026-91776 (GHSA-wv8q-qhhj-9h54), CVE-2026-91777 (GHSA-cxp5-3px4-pw24); Dependabot #33, #35, #36, #37; jackson-core CVE-2026-89425 (GHSA-7hhh-6rmp-j9qf), CVE-2026-89407 (GHSA-p6pp-m3f8-5c89), fixed in 2.21.7 and 3.1.7 | The Boot BOM manages ≥ 2.22.3 and ≥ 3.2.3. |
| `springdocOpenapi` | 3.1.1 (minimum) | Security release with eight advisories; two reach this deployment: the per-locale OpenAPI cache grew without bound through `Accept-Language` (now capped by `springdoc.cache.max-entries`), and a ThreadLocal leaked headers between concurrent requests. | — (not named individually) | Never go below 3.1.1. |
| `protobuf3` (keycloak-spi, `platform(libs.protobuf3.bom)`) | 3.25.9 | Keycloak 26.7.4, 26.8.0 and 26.8.1 → Quarkus → vertx-grpc → grpc-protobuf 1.65.0 brings protobuf-java 3.25.1. 3.25.9 is the newest patch on the 3.25 line grpc 1.65 was built for; 4.x leaves that line. Constraints only, nothing reaches the provider JAR. | CVE-2024-7254, Dependabot #12 The 26.8.0 image itself ships protobuf-java 4.35.0, but the Maven graph the SPI compiles against still resolves 3.25.1 through vertx-grpc, which is what the scan reads (checked 2026-10-01: dropping the floor made the OWASP scan report CVE-2024-7254 again). | `keycloak` resolves protobuf-java ≥ 3.25.5 on its own. |
| `vertx` (keycloak-spi, `platform(libs.vertx.bom)`) | 4.5.35 | Keycloak 26.8.1 → Quarkus brings Vert.x 4.5.34 into the Maven graph the SPI compiles against. 4.5.35 is the fixed patch on the same line. Constraints only, nothing reaches the provider JAR; the Keycloak image keeps its own Vert.x until a Keycloak release ships 4.5.35. | CVE-2026-102460 … CVE-2026-102477, CVE-2026-103223, CVE-2026-103224 (Vert.x `[4.0.0, 4.5.34]`) | `keycloak` resolves Vert.x ≥ 4.5.35 on its own. |
| `jackson2Plugins` (`buildscript` constraint on jackson-databind in the root and the frontend, and a constraint of `build-logic/build.gradle.kts`) | 2.22.4 | The OWASP dependency-check plugin 13.0.0 (open-vulnerability-clients 9.0.6 imports jackson-bom 2.22.1) and CycloneDX 3.5.1 (cyclonedx-core-java 13.2.0) bring 2.22.1 onto the root classpath; node-gradle 7.1.0 and openapi-generator 7.26.0 resolve the same on the frontend's. databind 2.22.4 aligns the rest of the family through its BOM. Build-time only. Stays on the 2.22 line these plugins ask for. | CVE-2026-68497, CVE-2026-19032, CVE-2026-83557; Dependabot #22, #25, #28; CVE-2026-91776, CVE-2026-91777; Dependabot #34, #38; CVE-2026-89425, CVE-2026-89407 (jackson-core 2.22.3) | These plugins bring jackson-databind ≥ 2.22.3 themselves. |
| `jackson3` (`buildscript` constraint in the frontend, and a constraint of `build-logic/build.gradle.kts`) | 3.2.4 | Spring Boot plugin → spring-boot-buildpack-platform 4.1.2 → tools.jackson 3.1.x. Build-time only: the buildpack code runs for `bootBuildImage`, which nothing calls (the images are built from Dockerfiles). Also the value of `jackson-bom.version`. | CVE-2026-68497, CVE-2026-19032, CVE-2026-83557; Dependabot #20, #23, #26; CVE-2026-91776, CVE-2026-91777; Dependabot #35, #37; CVE-2026-89425, CVE-2026-89407 (jackson-core 3.1.7) | `springBoot` brings tools.jackson ≥ 3.2.3 itself, with the Boot bump that drops `jackson-bom.version`. |
| `handlebars` (frontend `buildscript` constraint) | 4.5.8 | openapi-generator 7.26.0 pins handlebars 4.3.1 and upstream still does, so no plugin bump fixes it. Build-time only; generation uses the default Mustache engine. `handlebars-jackson2` stays at 4.3.1 (its last release, no advisory). | CVE-2026-55760, Dependabot #15 | `openapiGenerator` brings handlebars ≥ 4.5.2 itself. |
| `plexusUtils` (root `buildscript` constraint, and a constraint of `build-logic/build.gradle.kts`) | 3.6.1 | licensee 1.14.1 → maven-model-builder 3.9.11 pins plexus-utils 3.6.0. 3.6.1 is what the CycloneDX plugin on the same root classpath asks for, and subproject classloaders delegate to the root first. Build-time only. Do not take 4.x: it moved the `org.codehaus.plexus.util.xml` classes maven-model 3.9 needs into `plexus-xml` (see the `plexusUtils` row under *Held behind upstream*). | CVE-2025-67030, Dependabot #13 | `licensee` brings plexus-utils ≥ 3.6.1 itself. |
| `commonsLang3` (a constraint of `build-logic/build.gradle.kts`; the backend and ingest `buildscript` blocks are gone) | 3.21.0 | Spring Boot plugin → spring-boot-buildpack-platform → commons-compress 1.27.1 asks for 3.16.0. Never loaded at run time (the root classpath carries 3.21.0 and is asked first); the constraint makes the reported graph agree with what runs. 3.21.0 is what every other build classpath resolves. | CVE-2025-48924, GHSA-j288-q9x7-2f5v, Dependabot #19 | `springBoot` brings commons-lang3 ≥ 3.18.0 itself (commons-compress 1.28.0 does). |
| `jsoup` (root `buildscript` constraint) | 1.23.2 | The OWASP dependency-check plugin 13.0.0 → dependency-check-core brings jsoup 1.23.1 onto the root classpath. Build-time only. | CVE-2026-75140 (GHSA-65r4-943x-97jj), Dependabot #50 | `owaspDependencycheck` brings jsoup ≥ 1.23.2 itself. |
| `kotlinGradlePlugin` (`buildscript` constraint of `build-logic/build.gradle.kts`) | 2.4.21 | The `kotlin-dsl` plugin of Gradle 9.8.2 brings kotlin-gradle-plugin 2.4.10 onto the `build-logic` classpath. The constraint sits in `buildscript`, where `kotlin-dsl` resolves; an `implementation` constraint does not reach it. Build-time only. | CVE-2026-53914 (GHSA-r937-wjx7-w2jp), Dependabot #51 | The Gradle wrapper moves to a release whose `kotlin-dsl` brings kotlin-gradle-plugin ≥ 2.4.20. |
| `overrides` in `.github/tools/markdownlint/package.json` (`katex`, `minimatch`, `re2js`) | 0.18.2 / 11.0.3 / 2.9.1 | markdownlint-cli 0.50.0 (the newest release) pins minimatch ~10.2.6 and reaches katex 0.16.47 and re2js 2.8.6 through markdownlint 0.42.0 (micromark-extension-math, and a direct pin). It brings smol-toml, js-yaml and markdown-it at or above their former floors itself. CI-only tool; the lockfile is regenerated with `npm install --package-lock-only`. The tool replaced markdownlint-cli2 because cli2 globs through micromatch → braces, whose advisory GHSA-vfj7-8cjw-p6xm (stack exhaustion on nested brace patterns, `<= 3.0.3`) has no patched release; markdownlint-cli globs through tinyglobby and minimatch. | GHSA-238p-pmpm-9mq7 (CVE-2026-103923), Dependabot #48; GHSA-r4xh-jqrq-34v2, Dependabot #49; js-yaml `maxTotalMergeKeys` (5.0.0–5.4.0); GHSA-gfr2-rv44-r23c (CVE-2026-111758), Dependabot #53; GHSA-vfgh-jcmw-g37q (CVE-2026-111766), Dependabot #52; GHSA-g3hv-75wg-m249 (minimatch `< 11.0.1`); GHSA-gg6h-894w-4m9c (re2js 2.2.0–2.8.6) | markdownlint-cli brings katex ≥ 0.18.2, minimatch ≥ 11.0.1 and re2js ≥ 2.8.7 itself. |
| `overrides` in `frontend/package.json` (`glob`, `minimatch`) | ^14.0.3 / ^11.0.3 | htmlhint 1.9.2 (the newest release) asks for glob ^13, and eslint 10.14.0 and its config-array for minimatch ^10. Lint tooling only, never served. Both majors keep the API these tools call (same export map; `lintHtml`, `lintJs` green). | GHSA-3g3m-2fjr-6pw4 (glob 9.0.0–14.0.1); GHSA-g3hv-75wg-m249 (minimatch `< 11.0.1`) | htmlhint brings glob ≥ 14.0.2 and eslint minimatch ≥ 11.0.1 themselves. |

A floor only has to clear its advisory: stay on the lines above rather than taking a new major.

## Module-specific and tooling pins

| Entry | Value | Reason | Revisit when |
|---|---|---|---|
| `junit` (keycloak-spi only) | 6.1.3 | keycloak-spi is deliberately no Spring Boot module and has no BOM to supply JUnit. Boot 4.1.2 manages 6.0.3, so the repository runs two JUnit lines; harmless, as keycloak-spi shares no classpath with the others. | Restore the lockstep, or drop the pin, once the Boot BOM moves to 6.1.x. |
| `mockito` (keycloak-spi only) | 5.24.0 | Same reason as `junit`; backend, frontend and ingest keep Boot's managed Mockito. Kept in the catalog, not the build script, so refreshVersions can see it. The module's tests set `net.bytebuddy.experimental=true` because Byte Buddy does not yet officially support the JDK 25 toolchain. | Drop the Byte Buddy flag once Mockito's Byte Buddy officially supports JDK 25. |
| `test-support`, `logging-support` | Boot BOM | Both import the Spring Boot BOM without the Boot plugin and pin nothing of their own; the root script's BOM property overrides reach them through the dependency-management plugin, so they compile against what the applications run. No `platform(libs.junit.bom)` on top (removed 2026-09-22: it put a second JUnit line into one module). | — |
| `googleJavaFormat` | 2.0.0 (≥ 1.35.0) | Older google-java-format reflects on a javac method whose return type changed in JDK 25 and fails every file with `NoSuchMethodError`. | Keep ≥ 1.35.0. |
| `node` | 26.13.0 | The Node.js runtime the node-gradle plugin downloads; not a Maven artifact, so refreshVersions cannot track it. Regenerate the `win-x64` and `linux-x64` verification entries on a bump (see CONTRIBUTING → *Dependency verification*). | Bump by hand against the active Node LTS line (26, "Lithium"); the exchange-docs workflow's `setup-node` major moves with it. |
| pitest `addJUnitPlatformLauncher` (`build-logic`, `basetool.mutation-conventions`) | `false` | The plugin's launcher probe resolves a detached copy of `testImplementation` without the Boot BOM and its overrides. Nothing from it reaches a classpath, but the dependency-submission graph records it: in the backend it resolved tools.jackson 3.2.1 (json-schema-validator 3.0.7 asks for it), which raised Dependabot #40–#46 although every real configuration resolves 3.1.7. The pitest classpath includes the test runtime classpath, which already carries the Boot-managed `junit-platform-launcher` (declared `testRuntimeOnly` in backend and ingest, through `spring-boot-starter-test` in the frontend). | A pitest module's test runtime classpath loses the launcher. |
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

The 2026-10-19 sweep (#2772) took `flyway` 13.10.0 → 13.10.3 and `axeCorePlaywright` 4.13.0 →
4.14.0. Its `keycloak` 26.8.1 proposal is not refused: it moves only together with the image pin
(see the `keycloak` row above), in a change of its own.

The 2026-10-31 sweep took every stable release of the catalog, the Gradle wrapper, the actions, the
images, the Node.js LTS line and the npm and pip tooling, majors included, except the entries in
the table below; google-java-format 2.0.0 followed in a change of its own, which reformatted the code base. Boot 4.1.2 manages Tomcat 11.0.27 and Netty 4.2.19.Final, so the `tomcat.version`
and `netty.version` overrides are gone, and Lombok 1.18.48, so the keycloak-spi pin moved with it.
refreshVersions does not report every catalog entry (it missed `springBoot`, the plugin versions,
`googleJavaFormat`, `handlebars`, `kotlinGradlePlugin` and the Jackson keys); a sweep checks the
catalog against Maven Central and the plugin portal directly.

| Entry | Refused proposals | Reason |
|---|---|---|
| `springBoot` | 4.2.0 pre-releases | Not GA. |
| `mapstruct` | 1.7.0.Beta1, 1.7.0.Beta2 | Betas only. |
| `springModulith` | 2.2.0-M1, 2.2.0-M2, 2.2.0-RC1 | Pre-releases only, for Boot 4.2; test scope plus the `compileOnly` `spring-modulith-api` (REQ-MOD-006). |
| `plexusUtils` | 4.0.0 … 4.1.0 | Breaks licensee; see *Held behind upstream*. |
| `protobuf3` | 4.0.0-rc-1 … 4.36.2 | grpc-protobuf is still 1.65.0, built for the 3.x line; 3.25.9 is still the newest 3.25 patch. |
