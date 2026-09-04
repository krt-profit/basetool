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
# settings.gradle.kts includes all four modules, so Gradle configuration needs every module's
# build script present even when only :ingest is built.
COPY backend/build.gradle.kts backend/
COPY frontend/build.gradle.kts frontend/
COPY ingest/build.gradle.kts ingest/
COPY keycloak-spi/build.gradle.kts keycloak-spi/

# Cache the dependency layer. A failure here fails the build (no `|| true` mask).
RUN ./gradlew :ingest:dependencies --no-daemon

# Build the bootable jar, skipping the static-analysis / format / test gates (run separately in CI).
COPY ingest/src/ ingest/src/
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
RUN java -XX:ArchiveClassesAtExit=/app/application.jsa \
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
    fi

USER 10001:10001

EXPOSE 11262

# The gateway serves HTTPS with the shared self-signed cert (NPM terminates the public TLS and
# re-encrypts to this connector), so wget runs with --no-check-certificate. Readiness only goes UP
# after ApplicationReadyEvent, so a 200 means the context is up.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --start-interval=5s --retries=3 \
    CMD wget -q --no-check-certificate -O /dev/null https://localhost:11262/actuator/health/readiness || exit 1

# Conditional CDS: load the archive if training produced one, else start without it.
ENTRYPOINT ["sh", "-c", "if [ -f /app/application.jsa ]; then exec java -XX:SharedArchiveFile=/app/application.jsa -jar /app/app.jar; else exec java -jar /app/app.jar; fi"]
