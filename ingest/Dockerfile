# L-8: pin the base image to its multi-arch index digest (not just the floating tag) so builds are
# bit-reproducible and the SLSA provenance stays sound. Dependabot bumps the tag + digest together.
FROM eclipse-temurin:25-jdk-alpine@sha256:09349d79941fd53bb3d487b393ca118d8853c08c09193f416fe6a8718df9e732 AS build
WORKDIR /app

# Declared (unused) so the shared CI image matrix can pass --build-arg APP_VERSION=... uniformly.
ARG APP_VERSION=

COPY --chmod=755 gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY versions.properties .
COPY gradle.properties .
# settings.gradle.kts includes all five modules, so Gradle configuration needs every
# module's build script present even when only :ingest is built. A module added to
# settings.gradle.kts without a COPY here fails at SETTINGS evaluation, before any task
# runs: "Configuring project ':x' without an existing directory is not allowed". Only the
# build script is copied - no sources, so this costs nothing.
COPY backend/build.gradle.kts backend/
COPY frontend/build.gradle.kts frontend/
COPY ingest/build.gradle.kts ingest/
COPY keycloak-spi/build.gradle.kts keycloak-spi/
COPY test-support/build.gradle.kts test-support/

# Cache the dependency layer. A failure here fails the build (no `|| true` mask).
RUN ./gradlew :ingest:dependencies --no-daemon

# Build the bootable jar, skipping the static-analysis / format / test gates (run separately in CI).
COPY ingest/src/ ingest/src/
# The shared test-support MAIN sources too: `:ingest:build` still runs `compileTestJava`
# even with `-x test`, because the disabled `checkstyleTest` and
# `jacocoTestCoverageVerification` hang off `check` and depend on it. Since
# IngestEndpointSurfaceTest references `EndpointEnumeration` from that module, the image
# build fails to compile tests it will never run without these. Only `src/main`.
COPY test-support/src/main/ test-support/src/main/
RUN ./gradlew :ingest:build \
    -x test \
    -x checkstyleMain \
    -x spotbugsMain \
    -x spotlessCheck \
    -x spotlessJavaCheck \
    --no-daemon

# Extract the layered jar. Match the single bootable jar explicitly (skip -plain / -sources).
WORKDIR /app/ingest/build/libs
RUN set -eu; \
    matches=""; \
    for f in ingest-*.jar; do \
        case "$f" in \
            *-plain.jar|*-sources.jar) continue ;; \
            *.jar) matches="$matches $f" ;; \
        esac; \
    done; \
    set -- $matches; \
    [ "$#" -eq 1 ] && [ -f "$1" ] || { echo "Expected exactly one bootable ingest-*.jar, got $# match(es): '$*'" >&2; ls -la >&2; exit 1; }; \
    cp "$1" /app/app.jar

WORKDIR /app
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

WORKDIR /app/extracted

# Final image — Alpine JRE; every native bit we ship is pure Java (Lettuce / Netty are pure-Java).
# L-8: digest-pinned (see the build stage above).
FROM eclipse-temurin:25-jre-alpine@sha256:3137541deb3cac6626b5d9a4a2187bc0d6a34312f858bd2c67dd01e732e6b682
WORKDIR /app

# Non-root runtime user with a fixed UID/GID so host bind mounts can be pre-chowned.
# `apk upgrade` is the one apt/apk call these images make, and it is here because
# bumping the base digest is NOT enough. Measured 2026-09-04 against the promotion
# gate's own threshold (HIGH/CRITICAL, fixed only): the digest this file pinned
# carried 8 findings, the NEWEST upstream `25-jre-alpine` still carried 5, and the
# same image with this line carries 0. Eclipse Temurin rebuilds on its own cadence,
# so between two of its rebuilds the image lags Alpine's security updates -- and a
# faster Dependabot cannot close that window, because the newest tag is the thing
# that lags.
#
# It does not drift: the base pins its repositories to `v3.24`, so this upgrades
# WITHIN that Alpine release and never onto the next one. The digest above still
# fixes the starting point; this line patches it.
#
# Folded into the RUN below rather than added as its own layer, which is what the
# note about `apk add` just above is really about.
RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 app \
    && adduser  -S -u 10001 -G app -H -h /nonexistent -s /sbin/nologin app \
    && mkdir -p /app/logs \
    && chown -R 10001:10001 /app

COPY --from=build --chown=10001:10001 /app/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /app/extracted/application/ ./

# Spring Boot AppCDS training run. The OAuth2 issuer fetch and a live Redis are not reachable in the
# build container, so blank the issuer and lazy-init the rest; best-effort with a runtime fallback.
#
# -XX:+UseCompactObjectHeaders (2026-09-15, ADR-0180) MUST equal the runtime flag that
# docker-compose.yml passes in JAVA_TOOL_OPTIONS, and this line is the only place that can make the
# two agree: the training run happens during the image build, where JAVA_TOOL_OPTIONS is not set. The
# archive records the object layout it was dumped with, and a mismatch is NOT caught by the ENTRYPOINT
# fallback below — the .jsa exists, so the JVM rejects the archive ("does not equal the current
# UseCompactObjectHeaders setting") and starts normally without CDS. Nothing fails; the startup saving
# is simply gone. Verified 2026-09-15 on eclipse-temurin:25-jdk-alpine in both directions.
#
# The training run is a real application start, so Logback builds its file appenders and creates
# logs/ingest.log and logs/ingest-error.log relative to WORKDIR — as root, because `USER 10001:10001`
# comes below and a USER switch does not change an existing file's owner. Baked in, those files make
# the published image unable to start on its own: /app/logs is writable by 10001 (a NEW file there
# succeeds), but the app user cannot append to a root-owned one, and Logback turns that into
# "openFile(logs/ingest.log,true) call failed ... (Permission denied)" -> "Logback configuration error
# detected" -> exit 1, before any application code runs. docker-compose.yml bind-mounts
# /var/iri/ingest/log over /app/logs and hides them, which is what kept this latent in production and
# is not a fix. Cleared in the SAME RUN: a later layer would stack a whiteout on top and still ship
# the bytes underneath. The directory itself must survive, owned by the runtime user.
#
# Cleared after training rather than suppressed during it, deliberately. A console-only logging
# config would keep AsyncAppender, RollingFileAppender, SizeAndTimeBasedRollingPolicy and
# PiiMaskingPatternLayout out of the archive although every real startup loads them (measured on the
# ingest image, 2026-09-16: 56 classes and 278 KB less), and one that fails to parse guts the whole
# training run while still producing a .jsa — which the `if [ -f ... ]` fallback cannot detect, just
# like the object-layout mismatch above. A bare `-Dlogging.config=` suppresses nothing: Spring Boot
# reads an empty value as "no explicit config" and falls back to the classpath logback-spring.xml.
RUN java -XX:ArchiveClassesAtExit=/app/application.jsa \
        -XX:+UseCompactObjectHeaders \
        -Dspring.context.exit=onRefresh \
        -Dspring.main.lazy-initialization=true \
        -Dspring.security.oauth2.resourceserver.jwt.issuer-uri= \
        -Dserver.ssl.enabled=false \
        -jar app.jar 2>&1 || true ; \
    if [ -f /app/application.jsa ]; then \
        chown 10001:10001 /app/application.jsa ; \
        echo "[CDS] Archive created: $(wc -c < /app/application.jsa) bytes" ; \
    else \
        echo "[CDS] Archive not produced; runtime will start without CDS." ; \
    fi ; \
    rm -rf /app/logs && mkdir -p /app/logs && chown 10001:10001 /app/logs \
    && echo "[CDS] Training-run logs cleared; /app/logs ships empty."

USER 10001:10001

EXPOSE 11262

# The gateway serves HTTPS with the shared self-signed cert (NPM terminates the public TLS and
# re-encrypts to this connector), so wget runs with --no-check-certificate. Readiness only goes UP
# after ApplicationReadyEvent, so a 200 means the context is up.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --start-interval=5s --retries=3 \
    CMD wget -q --no-check-certificate -O /dev/null https://localhost:11262/actuator/health/readiness || exit 1

# Conditional CDS: load the archive if training produced one, else start without it.
ENTRYPOINT ["sh", "-c", "if [ -f /app/application.jsa ]; then exec java -XX:SharedArchiveFile=/app/application.jsa -jar /app/app.jar; else exec java -jar /app/app.jar; fi"]
