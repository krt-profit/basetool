# The three Spring Boot application images -- backend, frontend and ingest -- from ONE Dockerfile
# (IMG-SIMP-14, 2026-09-23). They were three near-identical files that differed in the module name,
# the port, the healthcheck's start period and the handful of flags each cache training run needs,
# and every cross-cutting fix (the `apk upgrade`, the compact object headers, the log clean-up) had
# to be made three times -- `ingest` was once left out of the hadolint run for two months because
# nobody noticed a third file.
#
#   docker build -f docker/app/Dockerfile --build-arg MODULE=backend .
#
# The build context is the REPOSITORY ROOT (the Gradle build is a multi-module build driven from
# there); `.dockerignore` at the root keeps secrets, build output and every module's `src/test` and
# `src/e2e` out of it. `MODULE` is the one required argument: it selects the Gradle project, the
# AOT-cache training stubs and -- through the last `FROM` -- the per-module tail stage that carries
# the port and the healthcheck. An unknown or missing value fails the build.
#
# Merging the three changed nothing the images ship: the same boot jar entry for entry, the same
# four extracted layers, the same user, port, labels and healthcheck (compared with
# `docker image inspect` and a full file listing on 2026-09-23). What DID change in the same change
# is deliberate and documented below: `bootJar` instead of `build` (BLD-PERF-01) and the Java AOT
# cache instead of the AppCDS archive, with an exec-form entrypoint (IMG-MOD-11, ADR-0209).
ARG MODULE

# L-8: pin the base image to its multi-arch index digest (not just the floating tag) so builds are
# bit-reproducible and the SLSA provenance stays sound. Dependabot bumps the tag + digest together
# (one `docker` entry for this directory in .github/dependabot.yml).
FROM eclipse-temurin:25-jdk-alpine@sha256:541729c21f9308a68cebbe5a0627e4cd465dfe8980fc03bac0b2feaee57daafd AS build
ARG MODULE
# Application version string forwarded to Gradle as `-PappVersion=...`. Only the frontend reads it
# (its `bootBuildInfo` stamps it into META-INF/build-info.properties, which the sidebar's version
# chip renders); the backend and ingest builds ignore the property. `.dockerignore` excludes `.git`,
# so `git describe` has nothing to work with in here -- CI computes the value on the runner
# (.github/scripts/app_version.py) and passes it as a build arg. Unset, the frontend falls back to
# the Gradle project version.
ARG APP_VERSION=
WORKDIR /app

# The Gradle wrapper and every build script first, so the dependency layer below is cached until
# one of them changes. `--chmod=755` on the wrapper avoids a separate `RUN chmod +x` layer.
#
# settings.gradle.kts includes all six modules, so Gradle configuration needs every module's build
# script present even when only one is built. A module added to settings.gradle.kts without a COPY
# here fails at SETTINGS evaluation, before any task runs: "Configuring project ':x' without an
# existing directory is not allowed". Only the build scripts -- no sources, so this costs nothing.
#
# `gradle/` carries the version catalog, the wrapper and verification-metadata.xml, so every
# artifact resolved in here is checked against its committed SHA-256 (REQ-OPS-034, strict).
COPY --chmod=755 gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .
COPY backend/build.gradle.kts backend/
COPY frontend/build.gradle.kts frontend/
COPY ingest/build.gradle.kts ingest/
COPY keycloak-spi/build.gradle.kts keycloak-spi/
COPY test-support/build.gradle.kts test-support/
COPY logging-support/build.gradle.kts logging-support/

# Resolve the module's dependencies into a cached layer. A failure here fails the build (no
# `|| true`: masking a wrapper download problem or a Maven Central 5xx only made the next step
# re-download everything silently). The `case` rejects a missing or unknown MODULE before Gradle
# turns it into a confusing "project ':' not found".
RUN case "$MODULE" in \
        backend|frontend|ingest) ;; \
        *) echo "MODULE must be backend, frontend or ingest (got '${MODULE}')" >&2; exit 1 ;; \
    esac; \
    ./gradlew ":${MODULE}:dependencies" --no-daemon

# The sources the boot jar is built from, and nothing else (BLD-PERF-01, 2026-09-23):
#   - the module's own `src/main`;
#   - logging-support's `src/main`, which SHIPS: every module's logback configuration names its
#     layout and encoder and the application code calls its LogSafe, so the boot jar carries it in
#     BOOT-INF/lib (ADR-0205);
#   - frontend/oss-bundled-components.json, the hand-kept non-Maven half of the frontend's
#     "Open-Source-Lizenzen" page (REQ-UI-021, ADR-0197), which `generateOssLicenses` reads into
#     the jar. Copied for every module because it is one small file and a per-module COPY needs a
#     glob trick; the backend and ingest builds never read it.
# No test-support, no backend openapi.json: those were only here because the image used to run
# `:<module>:build`, which compiled the TEST source set it never ran.
COPY ${MODULE}/src/main/ ${MODULE}/src/main/
COPY logging-support/src/main/ logging-support/src/main/
COPY frontend/oss-bundled-components.json frontend/oss-bundled-components.json

# `bootJar`, not `build` (BLD-PERF-01). `build` pulls in `check` -- tests, Checkstyle, SpotBugs,
# Spotless, and on the frontend nine Node-based linters, the TypeScript check and the OpenAPI
# emitter tests -- which the image used to switch off one `-x` at a time, and a task newly wired
# into `check` silently joined the image build until someone added it to the list (the image build
# broke that way on 2026-09-13). `bootJar` asks for exactly the artifact that ships, and the gates
# run where they belong: `./gradlew check` in CI. `${APP_VERSION:+...}` expands only when the build
# arg is set and non-empty.
RUN ./gradlew ":${MODULE}:bootJar" ${APP_VERSION:+-PappVersion=$APP_VERSION} --no-daemon

# The Spring Boot Gradle plugin produces exactly one bootable `<module>-<version>.jar`. Match it
# explicitly and fail loudly on zero or several candidates, instead of the old
# `find ... | head -n 1`, which silently picked whichever file the directory iterator surfaced
# first. The `-plain` / `-sources` skips are defensive: `bootJar` builds neither today, and a
# reintroduced classifier must not turn into a second match.
WORKDIR /app/${MODULE}/build/libs
RUN set -eu; \
    matches=""; \
    for f in "${MODULE}"-*.jar; do \
        case "$f" in \
            *-plain.jar|*-sources.jar) continue ;; \
            *.jar) matches="$matches $f" ;; \
        esac; \
    done; \
    set -- $matches; \
    [ "$#" -eq 1 ] && [ -f "$1" ] || { echo "Expected exactly one bootable ${MODULE}-*.jar, got $# match(es): '$*'" >&2; ls -la >&2; exit 1; }; \
    cp "$1" /app/app.jar

WORKDIR /app
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Runtime -- the Alpine variant of Temurin's JRE (~80 MB compressed, ~170 MB smaller than the jammy
# base). musl libc instead of glibc; safe because every native bit shipped is pure Java: openpdf
# renders with its 14 embedded Type-1 fonts (no fontconfig / freetype), no Tomcat APR connector,
# pure-Java PostgreSQL and Redis clients, Reactor Netty and Lettuce. The first native library that
# needs glibc ends this: switch back to `25-jre-jammy`. L-8: digest-pinned (see the build stage).
FROM eclipse-temurin:25-jre-alpine@sha256:2ca9adf44f5c29d28ecd26cf92d75cc0c66b7f32bfd839a4439e363a8b428af8 AS runtime
WORKDIR /app

# Non-root runtime user with a fixed UID/GID so host bind mounts (the log directory) can be
# pre-chowned: `chown -R 10001:10001 /var/iri/<module>/log`. Nothing is INSTALLED -- BusyBox's
# built-in `wget` (already in the base) serves the HEALTHCHECK -- and BusyBox addgroup/adduser
# (-S = system account) replace the Debian groupadd/useradd.
#
# `apk upgrade` is the one apk call these images make, and it is here because bumping the base
# digest is NOT enough. Measured 2026-09-04 against the promotion gate's own threshold
# (HIGH/CRITICAL, fixed only): the digest then pinned carried 8 findings, the NEWEST upstream
# `25-jre-alpine` still carried 5, and the same image with this line carried 0 -- Eclipse Temurin
# rebuilds on its own cadence and lags Alpine's security updates, so a faster Dependabot cannot
# close that window. It does not drift: the base pins its repositories to `v3.24`, so this upgrades
# WITHIN that Alpine release. Folded into one RUN so it adds no layer of its own.
RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 app \
    && adduser  -S -u 10001 -G app -H -h /nonexistent -s /sbin/nologin app \
    && mkdir -p /app/logs \
    && chown -R 10001:10001 /app

# The extracted layers, one COPY each, so a code change re-pushes only the application layer.
COPY --from=build --chown=10001:10001 /app/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /app/extracted/application/ ./

# The ahead-of-time cache (JEP 483/514/515, Java 25; IMG-MOD-11, ADR-0209 amending ADR-0180, 2026-09-23)
# -- the successor of the dynamic AppCDS archive these images carried until then. One training run
# starts the application exactly as far as the end of the Spring context refresh
# (`spring.context.exit=onRefresh`), and on exit the JVM writes `/app/app.aot`: the classes it
# loaded and linked, already parsed, plus method profiles. The runtime maps that file instead of
# loading those classes from the jars one by one.
#
# EAGER, NOT LAZY (IMG-PERF-12). The AppCDS run used `lazy-initialization`, and worse, it did not
# survive the refresh at all: the backend and frontend runs died at "Could not load store from
# 'classpath:keystore.p12'" (the keystore is mounted at runtime, never baked), so their archives
# held what a half-started context had touched -- and nothing noticed, because the `|| true` and
# the `if [ -f ... ]` fallback were built not to. The run below instantiates every singleton, and
# each module replaces exactly the external service it cannot reach in a build container:
#   all       TLS off (the keystore is not in the image); `spring.context.exit=onRefresh` exits
#             before any SmartLifecycle bean starts, so Tomcat never binds and no scheduler runs;
#   backend   a JDBC URL that resolves nowhere, Hikari skips its start-up probe, Flyway off,
#             `ddl-auto=none`, and Hibernate told its dialect and forbidden to read JDBC metadata,
#             so the EntityManagerFactory builds without a database; the resource server gets a JWK
#             set URI instead of an issuer (a decoder built from a JWK set URI fetches lazily, one
#             built from an issuer fetches at creation);
#   frontend  the registration points at a stub provider with explicit endpoints (no discovery
#             fetch) while `provider.keycloak.issuer-uri` stays set, because two beans read it by
#             `@Value`; a backend URL; a Redis host that resolves nowhere, which Lettuce only
#             dials on first use; and `app.session.configure-keyspace-notifications=false`, which
#             skips the one startup call that does dial it (RedisSessionConfig);
#   ingest    a JWK set URI instead of the issuer, as for the backend.
# `aot-training.invalid` is a reserved name (RFC 6761): it can never resolve, so a stub is never
# reached by accident. The default profile, not `prod`: the prod profile's startup guards
# (JwtAudienceStartupCheck and friends) refuse a configuration without real audiences, and working
# around each of them would train on a context no deployment runs either. The classes the two
# profiles share -- nearly all of them -- are what the cache is for.
#
# NOTHING HERE IS BEST-EFFORT ANY MORE (IMG-CI-13). A training run that fails to refresh, a cache
# that is not written, or a cache the runtime JVM would refuse FAILS THE BUILD, with the reason in
# the build log. The old design degraded silently in all three cases, and all three happened. When
# a new bean needs a live service at refresh time, the failure names it -- add its stub above.
#
# -XX:+UseCompactObjectHeaders (2026-09-15, ADR-0180) MUST equal the runtime flag docker-compose.yml
# and quadlet/env.d pass in JAVA_TOOL_OPTIONS, and this line is the only place that can make the two
# agree: the training run happens during the image build, where JAVA_TOOL_OPTIONS is not set. The
# cache records the object layout it was created with, and a JVM started with the other layout
# prints "Unable to use AOT cache. The AOT cache's UseCompactObjectHeaders setting (enabled) does not
# equal the current UseCompactObjectHeaders setting (disabled)" and starts WITHOUT it (verified
# 2026-09-23 on this base). The build's own check below runs with the same `$layout`, so it proves
# the cache matches what the image promises; a runtime that deviates from it is caught by the Loki
# rule JvmStartupCacheRejected instead. The garbage collector does NOT have to match: a cache trained
# under G1 is accepted by the ingest's Serial collector (verified the same day).
#
# The check is a second start under `-XX:AOTMode=on`, which turns "cache unusable" from a warning
# into a JVM that refuses to start. It costs one more context refresh (seconds) and it is the only
# test that asks the question the runtime will ask, with the runtime's own classpath.
#
# Both runs are real application starts, so Logback creates logs/<module>.log and
# logs/<module>-error.log relative to WORKDIR -- as root, because `USER 10001:10001` comes below and
# a USER switch does not re-own an existing file. Baked in, they make the published image unable to
# start on its own: the app user cannot append to a root-owned file, and Logback turns that into
# "Logback configuration error detected" -> exit 1 (REQ-OPS-031). Cleared in the SAME RUN -- a later
# layer would stack a whiteout and still ship the bytes -- and after training rather than
# suppressed during it: a console-only logging config would keep the appender and PII-masking
# classes every real startup loads out of the cache (ingest, 2026-09-16: 56 classes, 278 KB),
# and a bare `-Dlogging.config=` suppresses nothing (Spring Boot falls back to logback-spring.xml).
#
# BUILDKIT'S OWN OTEL VARIABLES ARE REMOVED FIRST. A docker-container BuildKit -- the builder
# release-images.yml uses, not the local default one -- hands every RUN step
# OTEL_TRACES_EXPORTER=otlp, OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=grpc and
# OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=unix:///dev/otel-grpc.sock so that build steps can report their
# own spans. Spring Boot reads them, builds a gRPC span exporter on that endpoint, and the exporter
# rejects anything but http(s) -- every module's refresh died at `otlpGrpcSpanExporter` (2026-09-23,
# the first release build of this file). Every OTEL_* variable is unset rather than the three by
# name, so a BuildKit that adds a fourth cannot bring it back; the runtime never sees these, only
# the build does.
#
# NO MACHINE CODE IN THE CACHE (`-XX:-AOTAdapterCaching -XX:-AOTStubCaching` on the training run). Creating a cache switches AOTAdapterCaching on by
# ergonomics, so JDK 25 also stores the interpreter/compiled-code adapters -- generated machine code
# -- in it. The image is built on one CI runner and started on others and on the hosts, with other
# CPUs: on 2026-09-23 the E2E backend died at start on 10 of 16 runners with `SIGILL ... Problematic
# frame: v ~AdapterBlob` after "Saved blob's name ... is different from the expected name" and
# "Failed to link AdapterHandlerEntry ... in the AOT code cache"; the 6 runners with the build
# runner's CPU started. What remains in the cache -- parsed and linked classes, method profiles --
# is data, not code, and does not depend on the CPU. Only the training run needs the flags: a cache
# that holds no adapters gives the runtime none to load. The verifying start below logs the code
# cache's state and FAILS THE BUILD unless the JVM reports "AOT Code Cache is empty" -- so a Temurin
# bump that starts caching some other kind of code cannot slip in silently either.
ARG MODULE
RUN set -eu; \
    env > /tmp/aot-env; \
    while IFS='=' read -r name _; do \
        case "$name" in OTEL_*) unset "$name" ;; esac; \
    done < /tmp/aot-env; \
    rm -f /tmp/aot-env; \
    layout="-XX:+UseCompactObjectHeaders"; \
    stub="aot-training.invalid"; \
    case "$MODULE" in \
        backend) set -- \
            -Dspring.datasource.url="jdbc:postgresql://${stub}:5432/none" \
            -Dspring.datasource.hikari.initialization-fail-timeout=-1 \
            -Dspring.flyway.enabled=false \
            -Dspring.jpa.hibernate.ddl-auto=none \
            -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
            -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
            -Dspring.security.oauth2.resourceserver.jwt.issuer-uri= \
            -Dspring.security.oauth2.resourceserver.jwt.jwk-set-uri="http://${stub}/certs" ;; \
        frontend) set -- \
            -Dspring.security.oauth2.client.provider.keycloak.issuer-uri="http://${stub}/realms/iri" \
            -Dspring.security.oauth2.client.registration.keycloak.provider=aot-training \
            -Dspring.security.oauth2.client.provider.aot-training.authorization-uri="http://${stub}/auth" \
            -Dspring.security.oauth2.client.provider.aot-training.token-uri="http://${stub}/token" \
            -Dspring.security.oauth2.client.provider.aot-training.jwk-set-uri="http://${stub}/certs" \
            -Dspring.security.oauth2.client.provider.aot-training.user-name-attribute=preferred_username \
            -Dapp.backend-url="https://${stub}" \
            -Dspring.data.redis.host="${stub}" \
            -Dapp.session.configure-keyspace-notifications=false ;; \
        ingest) set -- \
            -Dspring.security.oauth2.resourceserver.jwt.issuer-uri= \
            -Dspring.security.oauth2.resourceserver.jwt.jwk-set-uri="http://${stub}/certs" ;; \
    esac; \
    if ! java -XX:+UnlockDiagnosticVMOptions -XX:-AOTAdapterCaching -XX:-AOTStubCaching \
            -XX:AOTCacheOutput=/app/app.aot "$layout" \
            -Dspring.context.exit=onRefresh -Dserver.ssl.enabled=false "$@" \
            -jar app.jar > /tmp/aot-training.log 2>&1; then \
        cat /tmp/aot-training.log; \
        echo "[AOT] FAILED: the training run did not complete the context refresh (see above). A bean needs a live service at refresh time; give it a stub in docker/app/Dockerfile." >&2; \
        exit 1; \
    fi; \
    [ -s /app/app.aot ] || { cat /tmp/aot-training.log; echo "[AOT] FAILED: no cache written to /app/app.aot" >&2; exit 1; }; \
    echo "[AOT] Cache created: $(wc -c < /app/app.aot) bytes"; \
    if ! java -XX:AOTCache=/app/app.aot -XX:AOTMode=on "$layout" -Xlog:aot+codecache+init=info \
            -Dspring.context.exit=onRefresh -Dserver.ssl.enabled=false "$@" \
            -jar app.jar > /tmp/aot-check.log 2>&1; then \
        cat /tmp/aot-check.log; \
        echo "[AOT] FAILED: a JVM started with the runtime's layout ($layout) refuses the cache (see above)." >&2; \
        exit 1; \
    fi; \
    if ! grep -q "AOT Code Cache is empty" /tmp/aot-check.log; then \
        grep -i "codecache" /tmp/aot-check.log || true; \
        echo "[AOT] FAILED: the cache holds machine code (see above); it would crash on a host with another CPU. Keep -XX:-AOTAdapterCaching -XX:-AOTStubCaching on the training run." >&2; \
        exit 1; \
    fi; \
    echo "[AOT] Cache accepted by a start under -XX:AOTMode=on $layout, and it holds no machine code"; \
    chown 10001:10001 /app/app.aot; \
    rm -rf /tmp/aot-training.log /tmp/aot-check.log /tmp/tomcat.* /tmp/tomcat-docbase.*; \
    rm -rf /app/logs && mkdir -p /app/logs && chown 10001:10001 /app/logs; \
    echo "[AOT] Training-run logs cleared; /app/logs ships empty."

USER 10001:10001

# Exec form: the JVM is the process Docker starts, with no shell in between -- the `sh -c` wrapper
# existed only to fall back when no archive had been produced, and a cache this image could not use
# now fails its build instead. The `-XX:MaxRAMPercentage`, collector and object-layout flags live in
# JAVA_TOOL_OPTIONS (compose / quadlet env.d), which the JVM reads on its own. Should a deployment
# ever start with a layout the cache does not match, the default `-XX:AOTMode=auto` starts WITHOUT
# the cache and says so on stdout, which the Loki rule JvmStartupCacheRejected reads.
ENTRYPOINT ["java", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]

# ---------------------------------------------------------------------------------------------------
# Per-module tails. HEALTHCHECK is one of the Dockerfile instructions that expand no variables, so
# the port and the start period cannot come from a build arg; each module gets a three-line stage
# instead, and the final `FROM runtime-${MODULE}` picks one. BuildKit builds only the stages the
# selected target reaches, so the two unused tails cost nothing.
#
# All three probe `/actuator/health/readiness` with BusyBox wget, `--no-check-certificate` because
# each app serves HTTPS with the shared self-signed certificate (the edge terminates public TLS and
# re-encrypts). Readiness only goes UP after ApplicationReadyEvent, so a 200 means the context is
# fully initialised, not merely that Tomcat is bound; the `readiness` group carries LOCAL indicators
# only -- the Keycloak indicator is deliberately outside it (ADR-0084: a transient Keycloak blip
# flipped containers unhealthy and fed the 2026-07-09 deploy-loop thrash). Liveness stays unused on
# purpose: a failing readiness probe pulls an instance out of rotation, it does not restart it.
# `start-interval=5s` (Docker 25+) re-probes every 5 s inside the start window, so a fast boot is
# reported fast without spamming the steady-state schedule. wget forks an `ssl_client` per probe,
# which is why every service running these images needs `init: true` (REQ-OPS-019,
# .github/scripts/check_pid1_reaping.py reads the probes below).
# ---------------------------------------------------------------------------------------------------

# Backend: `start-period=90s` covers a slow Flyway migration plus JVM warm-up on a cold host.
FROM runtime AS runtime-backend
EXPOSE 11261
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --start-interval=5s --retries=3 \
    CMD wget -q --no-check-certificate -O /dev/null https://localhost:11261/actuator/health/readiness || exit 1

# Frontend: `start-period=90s` covers the OAuth2 issuer discovery plus JVM warm-up. Its readiness
# group also carries the backend and Redis indicators, so a backend outage takes it out of rotation.
FROM runtime AS runtime-frontend
EXPOSE 18081
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --start-interval=5s --retries=3 \
    CMD wget -q --no-check-certificate -O /dev/null https://localhost:18081/actuator/health/readiness || exit 1

# Ingest: no database and no migrations, so the start window is shorter.
FROM runtime AS runtime-ingest
EXPOSE 11262
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --start-interval=5s --retries=3 \
    CMD wget -q --no-check-certificate -O /dev/null https://localhost:11262/actuator/health/readiness || exit 1

# The image is the tail of the selected module. A MODULE outside backend|frontend|ingest names no
# stage here and fails the build (BuildKit tries to pull `runtime-<value>` as an image), and the
# build stage's `case` rejects it with a readable message as well. DL3006 ("always tag the
# version") does not apply: this names one of the three stages above, never a registry image,
# and hadolint cannot resolve a stage name built from an ARG.
# hadolint ignore=DL3006
FROM runtime-${MODULE}
