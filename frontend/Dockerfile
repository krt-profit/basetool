# L-8: pin the base image to its multi-arch index digest (not just the floating tag) so builds are
# bit-reproducible and the SLSA provenance stays sound. Dependabot bumps the tag + digest together.
FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151 AS build
WORKDIR /app

# Application version string forwarded to Gradle's `bootBuildInfo` via
# `-PappVersion=...`. `.dockerignore` excludes `.git` from the build context
# (intentional — keeps the build context small and is consistent with secrets
# policy), so `git describe` inside the container has nothing to work with.
# Instead the CI workflow computes the value on the runner (where `.git` is
# fully checked out with `fetch-depth: 0`) and passes it through as a
# `--build-arg`. Unset (= local `docker build` without the arg) falls back to
# the Gradle project version inside `frontend/build.gradle.kts`.
ARG APP_VERSION=

# Gradle-Wrapper und Konfigurationsdateien für Dependency-Caching kopieren.
# `--chmod=755` on the wrapper copy avoids a separate `RUN chmod +x gradlew`
# layer — BuildKit applies the bits at COPY time.
COPY --chmod=755 gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY versions.properties .
COPY gradle.properties .
COPY backend/build.gradle.kts backend/
COPY frontend/build.gradle.kts frontend/
# settings.gradle.kts includes all four modules, so Gradle configuration needs every module's
# build script present even when only :frontend is built.
COPY ingest/build.gradle.kts ingest/
COPY keycloak-spi/build.gradle.kts keycloak-spi/

# Dependencies herunterladen, um sie im Docker-Cache zu behalten. The previous
# `|| true` masked legitimate failures (wrapper download problems, transient
# Maven Central 5xx) and the next `:build` step would silently re-download
# everything anyway, so the cache layer became worthless without a visible
# error. If this step fails, the build now fails — fix the root cause instead.
RUN ./gradlew :frontend:dependencies --no-daemon

# Quellcode kopieren und Anwendung bauen.
# `-x test` skips the test suite; `-x checkstyleMain -x spotbugsMain
# -x spotlessCheck -x spotlessJavaCheck` skip the Java static-analysis and
# formatting verification steps, and `-x lintCss -x lintHtml -x lintJs
# -x prettierCheck -x typecheckJs -x generateApiTypes` skip the Node-based
# web-asset linters, the Prettier formatter and the TypeScript type check
# (ADR-0125) — all of which the `check` task pulls in via `build`. Those tools
# are meant for developer / CI workflows — running them inside the production
# image build only adds minutes and needs auxiliary files (`config/checkstyle/`,
# plus `frontend/package.json`, `frontend/tsconfig.json`, `frontend/types/` and
# the `.prettierrc.json` / `.prettierignore` / lint tool configs) that are
# deliberately not part of the Docker context (only `frontend/build.gradle.kts`
# and `frontend/src/` are copied). `generateApiTypes` additionally reads the
# BACKEND module's `openapi.json`, which is never in the frontend context at all.
# A node task whose declared input files are absent fails configuration
# validation outright, so every Node-based `check` task must be excluded here,
# not just the Java ones — when you add one to `check`, add it to this list in
# the same change or the image build breaks.
# Spotless in particular fails the build when source files were checked out with
# CRLF line endings (typical on a Windows-developer's workstation that pushes to
# a Linux build host), even though the byte content is otherwise identical to the
# LF copy in git. CI runs `./gradlew check` separately on a controlled
# environment where format enforcement is the right call.
COPY frontend/src/ frontend/src/
# `${APP_VERSION:+...}` only expands when the build-arg was set and non-empty,
# so a `docker build` without `--build-arg APP_VERSION=...` still works and the
# Gradle fallback in `frontend/build.gradle.kts` kicks in.
RUN ./gradlew :frontend:build \
    ${APP_VERSION:+-PappVersion=$APP_VERSION} \
    -x test \
    -x checkstyleMain \
    -x spotbugsMain \
    -x spotlessCheck \
    -x spotlessJavaCheck \
    -x lintCss \
    -x lintHtml \
    -x lintJs \
    -x prettierCheck \
    -x typecheckJs \
    -x generateApiTypes \
    --no-daemon

# Layered JAR extrahieren. The Spring Boot Gradle plugin produces exactly one
# bootable `frontend-<version>.jar` alongside `frontend-<version>-plain.jar`
# (the lightweight non-bootable classifier). The bootable jar is the one
# without a classifier — match it explicitly via a classifier-skip loop
# instead of the old `find … | head -n 1` pipeline, which silently picked
# whichever the directory iterator surfaced first. The loop also lets the
# script fail loudly when zero or >1 candidates remain (e.g. a future change
# adds another classifier).
WORKDIR /app/frontend/build/libs
RUN set -eu; \
    matches=""; \
    for f in frontend-*.jar; do \
        case "$f" in \
            *-plain.jar|*-sources.jar) continue ;; \
            *.jar) matches="$matches $f" ;; \
        esac; \
    done; \
    set -- $matches; \
    [ "$#" -eq 1 ] && [ -f "$1" ] || { echo "Expected exactly one bootable frontend-*.jar, got $# match(es): '$*'" >&2; ls -la >&2; exit 1; }; \
    cp "$1" /app/app.jar

WORKDIR /app
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

WORKDIR /app/extracted

# Finales Image — Alpine variant of Temurin's JRE (~80 MB compressed, ~170 MB
# smaller than the jammy base). musl libc instead of glibc; safe here because
# nothing in the frontend reaches outside the JVM via JNI — Thymeleaf SSR,
# WebClient (Reactor Netty), Spring Session + Lettuce (Redis client) are all
# pure-Java. If we ever add a native library that depends on glibc, revisit
# and switch back to `25-jre-jammy`.
# L-8: digest-pinned (see the build stage above).
FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
WORKDIR /app

# Non-root runtime user. Fixed UID/GID so host bind mounts (e.g. the log
# directory mounted in docker-compose) can be pre-chowned consistently:
#   chown -R 10001:10001 /var/iri/frontend/log
# No `apk add` for curl: BusyBox's built-in `wget` (already in the Alpine
# base) is enough for the HEALTHCHECK, and dropping the apk layer keeps the
# image surface a few MB smaller plus removes one network round-trip on
# rebuild. BusyBox addgroup/adduser (-S = system account, no password, no
# UID/GID range check) replace the Debian groupadd/useradd.
RUN addgroup -S -g 10001 app \
    && adduser  -S -u 10001 -G app -H -h /nonexistent -s /sbin/nologin app \
    && mkdir -p /app/logs \
    && chown -R 10001:10001 /app

# Extrahierte Layer einzeln kopieren (owned by the runtime user)
COPY --from=build --chown=10001:10001 /app/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /app/extracted/application/ ./

# Spring Boot AppCDS training run — writes a class-data-sharing archive next
# to the launcher jar so the runtime can load classes ~30-50% faster (typical
# Spring Boot startup-time reduction; results depend on which beans actually
# load during training).
#
# Context refresh in a fully-configured Spring Boot app triggers eager
# initialisation for infrastructure beans that require live external services
# (OAuth2 issuer metadata fetch, Redis client probe, Spring Session). None of
# those are reachable inside the build container, so `lazy-initialization`
# defers bean instantiation, the OAuth2 client issuer URI is blanked so no
# `.well-known/openid-configuration` HTTP call is attempted, and the Redis
# host is pointed at a non-resolving alias (Lettuce will try lazily, never
# at refresh time).
#
# Best-effort: if any future refactor breaks context refresh under these
# flags, training fails silently and the runtime ENTRYPOINT below detects
# the missing .jsa file and falls back to a non-CDS startup. Build logs
# carry the JVM stack trace so CI can surface the regression.
RUN java -XX:ArchiveClassesAtExit=/app/application.jsa \
        -Dspring.context.exit=onRefresh \
        -Dspring.main.lazy-initialization=true \
        -Dspring.security.oauth2.client.provider.keycloak.issuer-uri= \
        -Dspring.data.redis.host=cds-stub \
        -Dspring.session.store-type=none \
        -jar app.jar 2>&1 || true ; \
    if [ -f /app/application.jsa ]; then \
        chown 10001:10001 /app/application.jsa ; \
        echo "[CDS] Archive created: $(wc -c < /app/application.jsa) bytes" ; \
    else \
        echo "[CDS] Archive not produced; runtime will start without CDS." ; \
    fi

USER 10001:10001

EXPOSE 18081

# Hit the Spring Boot Actuator readiness probe. The frontend serves HTTPS with a
# self-signed certificate (see application.yml `server.ssl`) so wget runs with
# `--no-check-certificate` to skip cert verification.
#
# Timing: `start-period=90s` covers the OAuth2 issuer-discovery round-trip plus
# the JVM warm-up window — a stricter limit flaps the container into `unhealthy`
# on cold-cache servers even when the app is healthy. `start-interval=5s`
# (Docker 25+) re-probes every 5 s during the start window so a fast boot is
# reported fast, without spamming the runtime probe schedule (interval=30s).
#
# Why /actuator/health/readiness instead of /actuator/health:
#   1. Spring's `readinessState` indicator only goes UP after ApplicationReadyEvent,
#      so a 200 here guarantees the context is fully initialised (OAuth2 client
#      registrations resolved, Spring Session ready, scheduled tasks armed) —
#      not just "Tomcat is bound".
#   2. The `readiness` health group in application.yml widens the set of indicators
#      contributing to this endpoint (backend, redis, diskSpace, ssl), so an actual
#      backend outage drops readiness DOWN and any orchestrator gating on
#      `service_healthy` will not route traffic to this instance. The custom
#      KeycloakHealthIndicator is deliberately NOT in the group (ADR-0084): a
#      transient Keycloak blip must not flip this container `unhealthy` and feed
#      the deploy-loop thrash of the 2026-07-09 incident. Keycloak health stays
#      observable via the root /actuator/health aggregate + WARN logs.
# Liveness (/actuator/health/liveness) stays unused here on purpose — a failing
# readiness probe should pull this instance out of the rotation, not restart it.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --start-interval=5s --retries=3 \
    CMD wget -q --no-check-certificate -O /dev/null https://localhost:18081/actuator/health/readiness || exit 1

# Conditional CDS: if the training run above produced an archive, load it; if
# not, start without CDS so the image still works. The `-jar app.jar` and
# `-XX:MaxRAMPercentage` settings live in JAVA_TOOL_OPTIONS (from compose) so
# they apply in both branches without duplication.
ENTRYPOINT ["sh", "-c", "if [ -f /app/application.jsa ]; then exec java -XX:SharedArchiveFile=/app/application.jsa -jar /app/app.jar; else exec java -jar /app/app.jar; fi"]
