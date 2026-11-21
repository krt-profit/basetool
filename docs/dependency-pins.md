# Dependency pins, holds and floors

> **Doc type:** Living reference — kept in sync with `main`. Last reviewed: 2026-11-21.

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
| `plexusUtils` | 3.6.4 | 4.x moved `org.codehaus.plexus.util.xml` into `plexus-xml`; licensee's maven-model-builder 3.9 still needs it there (`NoClassDefFoundError: org/codehaus/plexus/util/xml/pull/XmlPullParserException` from `:backend:licensee` and `:frontend:licensee` with 4.1.0, 2026-10-31). Also a security floor, see below. | `licensee` moves to Maven 4's model builder. |
| `mapstruct` | 1.6.3 | The only newer builds are 1.7.0 betas (Beta1, Beta2); stay on the 1.6.x stable line. | 1.7.0 final is released. |
| arm64 runner label (`promote.yml`, `release-images.yml`, `sandbox-images.yml`) | `ubuntu-24.04-arm` | `ubuntu-latest` resolves to ubuntu-26.04, so the x64 legs build on 26.04, but actionlint 1.7.12 (the newest release, run by `repo-lint.yml`) does not know the `ubuntu-26.04-arm` label and fails on it. | actionlint lists the 26.04 runner labels. |
| JWKS decoders on `RestTemplate` (backend and ingest `SecurityConfig.buildDecoder`, frontend `KeycloakBackchannelConfig` ID-token decoder) | `RestTemplate` | Spring Framework 7.1 deprecates `RestTemplate` and `RestOperations` for removal, but Spring Security 7.2.0's `NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder` takes a custom HTTP client only as `restOperations(RestOperations)`, so the pinned `keycloak-trust` JWKS fetch (REQ-SEC-024, REQ-SEC-087) keeps one. Each use is a `[removal]` warning on a full recompile. Tracked in #3564. | Spring Security ships a `RestClient` successor ([spring-security#19027](https://github.com/spring-projects/spring-security/issues/19027)). |
| Frontend userinfo call on `RestTemplate` (`KeycloakBackchannelConfig.oidcUserService`) | `RestTemplate` | Spring Security 7.2.0's `DefaultOAuth2UserService` takes a custom HTTP client only as `setRestOperations(RestOperations)`, the one way to keep the userinfo call on the internal connector with the pinned trust (REQ-SEC-087). A `[removal]` warning on a full recompile. Tracked in #3565. | Spring Security ships a `RestClient` successor ([spring-security#19027](https://github.com/spring-projects/spring-security/issues/19027)). |

## Pinned to match another component

| Entry | Value | Must equal | Reason | Revisit when |
|---|---|---|---|---|
| `lombok` (keycloak-spi only) | 1.18.48 | Boot-managed Lombok (1.18.48 under Boot 4.2.0) | `lombok.config` is one shared file; two Lombok versions would read it two ways. Every other module takes Lombok from the Boot BOM. Check with `./gradlew :backend:dependencyInsight --configuration compileClasspath --dependency org.projectlombok:lombok`. | Move together with the Boot bump that moves the managed version. |
| `keycloak` (keycloak-spi `compileOnly` SPI artifacts) | 26.8.2 | The Keycloak runtime image `quay.io/keycloak/keycloak:26.8` | Compile against the highest 26.8.x patch, the one the `26.8` line resolves to; SPIs are stable within a minor, and an exact match avoids load-time surprises. `scripts/check-keycloak-version.py` (`repo-lint.yml`, REQ-OPS-040) fails when the catalog and the three image pins name different minors. | A new 26.8.x patch, or the image moves to another minor. |
| keycloak-spi `options.release` (`basetool { javaRelease = 21 }` in `keycloak-spi/build.gradle.kts`) | 21 | The JDK in the Keycloak 26.8 image (OpenJDK 21.0.13.1, read from the pinned 26.8.2-3 image on 2026-11-20) | The provider JAR is loaded by Keycloak's JDK 21; newer bytecode fails with `UnsupportedClassVersionError`. Compiled with the JDK 27 toolchain, emitted as `--release 21`. | Keycloak's image moves to a newer JDK. |
| `checkstyle` | 14.4.0 | `config/checkstyle/google_checks.xml` | That file is the `google_checks.xml` of the `checkstyle-14.4.0` release tag with exactly two edits: its XML comments are removed (ADR-0214), and `EmptyCatchBlock` takes `exceptionVariableName` `^(ignored\|expected\|_)$` instead of `commentFormat`, because an empty `catch` names its variable rather than carrying a comment (`_` since ADR-0214's D-14 amendment). An older config on a newer tool silently skips every rule added since. | Re-copy the file from the matching release tag in the commit that bumps `checkstyle`, then re-apply the two edits; nothing else may differ from upstream. |

## Security floors and overrides

| Entry | Value | Reason | Advisories | Drop when |
|---|---|---|---|---|
| `jackson-2-bom.version` and `jackson-bom.version` Boot BOM properties (`build-logic`, `basetool.dependency-management-conventions`, values from `jackson2` and `jackson3`) | 2.23.0 / 3.3.0 | Boot 4.2.0 manages 2.21.8 and 3.1.8 (3.3.x is two minors newer than the 3.1 line Boot 4.2 builds on; taken with the full test suite green). Jackson 3 ships in all three apps, Jackson 2 in the backend only. Like every BOM property here it is set where the dependency-management plugin is applied, so `logging-support` (logstash-logback-encoder → Jackson 3) and `test-support` get it too; set for Boot-plugin modules only, it would leave `logging-support` on 3.1.8. Set as a BOM property: in `gradle.properties` it is silently overridden, and a plain constraint collides with the BOM's `strictly` constraints. | CVE-2026-68497 (GHSA-q4xh-88c3-wmh7), CVE-2026-19032 (GHSA-wjgm-6hv5-3cvf), CVE-2026-83557 (GHSA-gx83-3vf8-gh7j); Dependabot #20, #23, #26; CVE-2026-91776 (GHSA-wv8q-qhhj-9h54), CVE-2026-91777 (GHSA-cxp5-3px4-pw24); Dependabot #33, #35, #36, #37; jackson-core CVE-2026-89425 (GHSA-7hhh-6rmp-j9qf), CVE-2026-89407 (GHSA-p6pp-m3f8-5c89), fixed in 2.21.7 and 3.1.7 | The Boot BOM manages ≥ 2.22.3 and ≥ 3.2.3. |
| `springdocOpenapi` | 3.1.1 (minimum) | Security release with eight advisories; two reach this deployment: the per-locale OpenAPI cache grew without bound through `Accept-Language` (now capped by `springdoc.cache.max-entries`), and a ThreadLocal leaked headers between concurrent requests. | — (not named individually) | Never go below 3.1.1. |
| `protobuf3` (keycloak-spi, `platform(libs.protobuf3.bom)`) | 3.25.9 | Keycloak 26.7.4, 26.8.0, 26.8.1 and 26.8.2 → Quarkus → vertx-grpc → grpc-protobuf 1.65.0 brings protobuf-java 3.25.1. 3.25.9 is the newest patch on the 3.25 line grpc 1.65 was built for; 4.x leaves that line. Constraints only, nothing reaches the provider JAR. | CVE-2024-7254, Dependabot #12 The 26.8.0 image itself ships protobuf-java 4.35.0, but the Maven graph the SPI compiles against still resolves 3.25.1 through vertx-grpc, which is what the dependency graph and Dependabot read (checked 2026-10-01: dropping the floor made the dependency scan report CVE-2024-7254 again). | `keycloak` resolves protobuf-java ≥ 3.25.5 on its own. |
| `jackson2Plugins` (`buildscript` constraint on jackson-databind in the root and the frontend, and a constraint of `build-logic/build.gradle.kts`) | 2.23.0 | CycloneDX 3.6.1 (cyclonedx-core-java 13.4.0) brings 2.22.1 onto the root classpath; node-gradle 7.1.0 and openapi-generator 7.27.0 resolve the same on the frontend's. databind 2.23.0 aligns the rest of the family through its BOM and matches `jackson2`. Build-time only. | CVE-2026-68497, CVE-2026-19032, CVE-2026-83557; Dependabot #22, #25, #28; CVE-2026-91776, CVE-2026-91777; Dependabot #34, #38; CVE-2026-89425, CVE-2026-89407 (jackson-core 2.22.3) | These plugins bring jackson-databind ≥ 2.22.3 themselves. |
| `jackson3` (`buildscript` constraint in the frontend, and a constraint of `build-logic/build.gradle.kts`) | 3.3.0 | Spring Boot plugin → spring-boot-buildpack-platform 4.2.0 → tools.jackson 3.1.8. Build-time only: the buildpack code runs for `bootBuildImage`, which nothing calls (the images are built from Dockerfiles). Also the value of `jackson-bom.version`. | CVE-2026-68497, CVE-2026-19032, CVE-2026-83557; Dependabot #20, #23, #26; CVE-2026-91776, CVE-2026-91777; Dependabot #35, #37; CVE-2026-89425, CVE-2026-89407 (jackson-core 3.1.7) | `springBoot` brings tools.jackson ≥ 3.2.3 itself, with the Boot bump that drops `jackson-bom.version`. |
| `handlebars` (frontend `buildscript` constraint) | 4.5.8 | openapi-generator 7.27.0 pins handlebars 4.3.1 and upstream still does, so no plugin bump fixes it. Build-time only; generation uses the default Mustache engine. `handlebars-jackson2` stays at 4.3.1 (its last release, no advisory). | CVE-2026-55760, Dependabot #15 | `openapiGenerator` brings handlebars ≥ 4.5.2 itself. |
| `plexusUtils` (root `buildscript` constraint, and a constraint of `build-logic/build.gradle.kts`) | 3.6.4 | licensee 1.14.1 → maven-model-builder 3.9.11 pins plexus-utils 3.6.0, and the CycloneDX 3.6.1 plugin on the same root classpath asks for 3.6.2; subproject classloaders delegate to the root first. 3.6.4, the newest 3.x patch, puts one version on every build classpath. Build-time only. Do not take 4.x: it moved the `org.codehaus.plexus.util.xml` classes maven-model 3.9 needs into `plexus-xml` (see the `plexusUtils` row under *Held behind upstream*). | CVE-2025-67030, Dependabot #13 | `licensee` brings plexus-utils ≥ 3.6.1 itself. |
| `commonsLang3` (a constraint of `build-logic/build.gradle.kts`; the backend and ingest `buildscript` blocks are gone) | 3.21.0 | Spring Boot plugin → spring-boot-buildpack-platform → commons-compress 1.27.1 asks for 3.16.0. Never loaded at run time (the root classpath carries 3.21.0 and is asked first); the constraint makes the reported graph agree with what runs. 3.21.0 is what every other build classpath resolves: handlebars 4.5.8 asks for it on the frontend's `buildscript` classpath, so a higher value here (3.22.0 is out) would run a version that graph does not report. | CVE-2025-48924, GHSA-j288-q9x7-2f5v, Dependabot #19 | `springBoot` brings commons-lang3 ≥ 3.18.0 itself (commons-compress 1.28.0 does). |
| `overrides` in `.github/tools/markdownlint/package.json` (`katex`, `minimatch`) | 0.19.1 / 11.0.9 | markdownlint-cli 0.50.0 (the newest release) pins minimatch ~10.2.6 and reaches katex 0.16.47 through markdownlint 0.42.2 (micromark-extension-math). It brings re2js (markdownlint 0.42.2 pins 2.9.1), smol-toml, js-yaml and markdown-it at or above their former floors itself. CI-only tool; Dependabot's npm entry for the directory proposes its bumps, and by hand the lockfile is regenerated with `npm install --package-lock-only`. The tool replaced markdownlint-cli2 because cli2 globs through micromatch → braces, whose advisory GHSA-vfj7-8cjw-p6xm (stack exhaustion on nested brace patterns, `<= 3.0.3`) has no patched release; markdownlint-cli globs through tinyglobby and minimatch. | GHSA-238p-pmpm-9mq7 (CVE-2026-103923), Dependabot #48; GHSA-r4xh-jqrq-34v2, Dependabot #49; js-yaml `maxTotalMergeKeys` (5.0.0–5.4.0); GHSA-gfr2-rv44-r23c (CVE-2026-111758), Dependabot #53; GHSA-vfgh-jcmw-g37q (CVE-2026-111766), Dependabot #52; GHSA-g3hv-75wg-m249 (minimatch `< 11.0.1`) | markdownlint-cli brings katex ≥ 0.18.2 and minimatch ≥ 11.0.1 itself. |
| `overrides` in `frontend/package.json` (`glob`) | ^14.0.6 | htmlhint 1.9.2 (the newest release) asks for glob ^13. Lint tooling only, never served. glob 14 keeps the API htmlhint calls (same export map; `lintHtml` green), and every package that reaches minimatch, glob 14 and eslint 10.15.0 included, asks for ^11.0.5 itself. | GHSA-3g3m-2fjr-6pw4 (glob 9.0.0–14.0.1) | htmlhint brings glob ≥ 14.0.2 itself. |

A floor only has to clear its advisory: stay on the lines above rather than taking a new major.

## Module-specific and tooling pins

| Entry | Value | Reason | Revisit when |
|---|---|---|---|
| `junit` (keycloak-spi only) | 6.1.3 | keycloak-spi is deliberately no Spring Boot module and has no BOM to supply JUnit. Boot 4.2.0 manages the same 6.1.3, so the repository runs one JUnit line. | Move together with the Boot bump that moves the managed JUnit. |
| `mockito` (keycloak-spi only) | 5.24.0 | Same reason as `junit`; backend, frontend and ingest keep Boot's managed Mockito, 5.24.0 under Boot 4.2.0. Kept in the catalog, not the build script, so refreshVersions can see it. The module's tests set `net.bytebuddy.experimental=true`: without the Boot BOM, Mockito 5.24.0 brings Byte Buddy 1.17.7, which officially supports class files up to Java 25, and the toolchain is JDK 27. | Drop the Byte Buddy flag once Mockito brings Byte Buddy 1.18.9 or later (Java 27 support). |
| `test-support`, `logging-support` | Boot BOM | Both import the Spring Boot BOM without the Boot plugin and pin nothing of their own; the BOM property overrides of `basetool.dependency-management-conventions` reach them through the dependency-management plugin, so they compile against what the applications run. No `platform(libs.junit.bom)` on top (removed 2026-09-22: it put a second JUnit line into one module). | — |
| Java toolchain, `setup-java` `java-version` and the `eclipse-temurin:27-*-alpine` images | 27 | A feature release, not an LTS: the owner prefers the newest stable JDK and takes each one within its six-month support window ([ADR-0398](adr/0398-the-stack-leaves-the-java-25-lts-for-java-27.md)). | JDK 28 ships (March 2027) and a Spring Boot release supports it; Java 29 (LTS) follows in September 2027. |
| `jacoco` (`basetool.coverage-conventions`) | 0.8.16 | Gradle's default JaCoCo (0.8.15 under Gradle 9.9) reads Java 27 class files only through its experimental path; 0.8.16 supports them. | Keep it at the newest release; it must read the class-file version of the toolchain. |
| `pitestCore` (`pitestVersion` in `basetool.mutation-conventions`) | 1.30.2 | The pitest Gradle plugin 1.19.0 defaults to PIT 1.22.1, whose bundled ASM reads class files up to Java 26 only; PIT reads Java 27 from 1.25.6 on. | Keep it at the newest release; it must read the class-file version of the toolchain. |
| `googleJavaFormat` | 2.0.0 (≥ 1.35.0) | Older google-java-format reflects on a javac method whose return type changed in JDK 25 and fails every file with `NoSuchMethodError`. | Keep ≥ 1.35.0. |
| `node` | the 26 line | The Node.js runtime the node-gradle plugin downloads; not a Maven artifact, so refreshVersions cannot track it. `native-tool-updates.yml` proposes the newest release of the line with both verification entries (REQ-OPS-059). | Move to the next active LTS line by hand; the exchange-docs workflow's `setup-node` major moves with it. |
| pitest `addJUnitPlatformLauncher` (`build-logic`, `basetool.mutation-conventions`) | `false` | The plugin's launcher probe resolves a detached copy of `testImplementation` without the Boot BOM and its overrides. Nothing from it reaches a classpath, but the dependency-submission graph records it: in the backend it resolved tools.jackson 3.2.1 (json-schema-validator 3.0.7 asks for it), which raised Dependabot #40–#46 although every real configuration resolves 3.1.7. The pitest classpath includes the test runtime classpath, which already carries the Boot-managed `junit-platform-launcher` (declared `testRuntimeOnly` in backend and ingest, through `spring-boot-starter-test` in the frontend). | A pitest module's test runtime classpath loses the launcher. |
| refreshVersions plugin (`settings.gradle.kts`) | 0.60.6 | Declared `apply false` so it still resolves, stays pinned and stays in the dependency verification metadata; applied only with `-PrefreshVersions` because it is not configuration-cache compatible. | Bump by hand. |

## Deliberate exclusions

| Where | Excluded | Reason | Revisit when |
|---|---|---|---|
| backend `spring-boot-starter-data-jpa` | `org.springframework:spring-aspects`, `org.aspectj:aspectjweaver` | Licence, not size (ADR-0197): the AspectJ jar is `EPL-2.0 AND BSD-3-Clause AND Apache-1.1` with no GPL secondary licence and may not ship in a GPL-3.0-only image. Nothing uses AspectJ; the proxy-based advisors (`@Transactional`, `@PreAuthorize`, `@Cacheable`, `@Async`, `@Scheduled`) work without it. `:backend:licensee` refuses EPL-2.0 if a new path brings it back. | Never, while the images are GPL-3.0-only. |
| backend, frontend, ingest `spring-boot-starter-opentelemetry` | `io.micrometer:micrometer-registry-otlp` | It would switch on Boot's OTLP metrics push to a localhost default endpoint in every environment. Metrics are Prometheus pull only (REQ-OBS-005). | Metrics export moves to OTLP. |

## Pins outside Dependabot

`.github/dependabot.yml` covers the GitHub Actions, the Docker and Compose images, the npm packages
of `frontend/` and `.github/tools/markdownlint/`, and the CI's Python tools: its `uv` entry
recompiles each `.github/requirements/<name>.txt` from the `<name>.in` beside it, with the options
the file's uv header names. Keycloak is ignored there (`scripts/check-keycloak-version.py` moves the
library and the image together), and `quadlet/` follows the compose files (ADR-0215). The Gradle
graph is refreshVersions', except the Ivy modules `ruff`, `shfmt` and `node`, which
`native-tool-updates.yml` proposes (REQ-OPS-059), and the refreshVersions plugin above.

Dependabot cannot see the pins below. Each is bumped by hand, its version and checksum in the same
change.

| Pin | Where | Value | How it moves |
|---|---|---|---|
| `TRIVY_VERSION` | workflow `env` of `release-images.yml`, `promote.yml`, `vulnerability-scan.yml` and `sandbox-images.yml` | v0.76.2 | Passed as `version` to `aquasecurity/trivy-action`, which otherwise installs its own default: v0.70.0 in action v0.36.0, affected by CVE-2026-55092 (GHSA-mcj4-mphf-j9ff), CVE-2026-54448 (GHSA-q3fv-x8vg-qqm4) and CVE-2026-63328 (GHSA-8rc5-4fr6-64pw). Dependabot bumps the action, never this value. Bump on a Trivy release, all four together. |
| `ACTIONLINT_VERSION`, `ACTIONLINT_SHA256` | job `env` of *Linters* in `repo-lint.yml` | 1.7.12 | The `actionlint_<version>_linux_amd64.tar.gz` release asset and its SHA-256 from the release's checksums file. |
| `HADOLINT_VERSION`, `HADOLINT_SHA256` | job `env` of *Linters* in `repo-lint.yml` | 2.15.1 | The `hadolint-linux-x86_64` release asset and its SHA-256 from the release's `checksums.sha256`. |
| `GITLEAKS_VERSION`, `GITLEAKS_SHA256` | workflow `env` of `commit-gates.yml` | 8.30.1 | The `gitleaks_<version>_linux_x64.tar.gz` release asset and its SHA-256 from the release's checksums file. |
| `REDOC_VERSION`, `REDOC_INTEGRITY` | step `env` of *Add the pinned Redoc bundle* in `exchange-docs.yml` | 3.1.0 | The `redoc` npm tarball, fetched with `npm pack`; the integrity is its registry `dist.integrity` (sha512). |
| cosign | default `cosign-release` of `sigstore/cosign-installer` (pinned at v4.1.3) | v3.1.4 | No workflow sets `cosign-release`, so cosign moves when Dependabot bumps the installer; read the new default in its `action.yml` before merging that bump. |
| `LOKITOOL_VERSION`, `LOKITOOL_SHA256` | `scripts/check-loki-rules.sh` | 3.7.10 | The `lokitool-linux-amd64.zip` asset of the grafana/loki release and its SHA-256 from the release's `SHA256SUMS`. Keep the version equal to the `grafana/loki` image in `docker-compose.monitoring.yml`, so the rules are linted with the LogQL parser the server runs. lokitool replaced the archived cortex-tools; `grafana/mimirtool` lints Mimir rules only. |
| `RUNNER_IMAGE` | `scripts/check-loki-rules.sh` | `alpine:3@sha256:f59abe656b69…` | The sealed container (`--network none`) lokitool runs in. Re-resolve the `alpine:3` index digest with `docker buildx imagetools inspect`. |
| Ansible collections | `ansible/requirements.yml` | community.general 14.0.1, ansible.posix 2.2.2 | Exact versions; Galaxy publishes no hash a requirements file could pin, and Dependabot has no Galaxy ecosystem. |

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

The 2026-11-09 sweep did the same and took everything stable again, except the entries in the table
below and the Java 25 and `ansible-core` holds under *Held behind upstream*. The Gradle 9.9.0
wrapper brings kotlin-gradle-plugin 2.4.21 itself, so the `kotlinGradlePlugin` floor is gone.

The 2026-11-19 sweep took every stable release published since: `playwright` 1.65.1, `errorprone`
2.53.0, `nullaway` 0.14.4, the rebuilt `postgres:18-alpine` and `redis:8-alpine` digests,
postgres-exporter v0.21.1, `actions/dependency-review-action` v5.0.1,
`actions/create-github-app-token` v3.4.0 and the npm lockfile patches. `ubuntu-latest` runs
ubuntu-26.04 with Python 3.14, so the `ansible-core` hold is gone (2.22.0) and every requirement
file is compiled for 3.14; the arm64 runner label is held (see *Held behind upstream*). Spring Boot
4.2.0, Spring Modulith 2.2.0, PostgreSQL 19 and the node-exporter v1.13.0 image were not yet
published as stable releases.

| Entry | Refused proposals | Reason |
|---|---|---|
| `mapstruct` | 1.7.0.Beta1, 1.7.0.Beta2 | Betas only. |
| `plexusUtils` | 4.0.0 … 4.1.0 | Breaks licensee; see *Held behind upstream*. |
| `protobuf3` | 4.0.0-rc-1 … 4.36.2 | grpc-protobuf is still 1.65.0, built for the 3.x line; 3.25.9 is still the newest 3.25 patch. |
