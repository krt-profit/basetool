ARG MODULE

FROM eclipse-temurin:25-jdk-alpine@sha256:541729c21f9308a68cebbe5a0627e4cd465dfe8980fc03bac0b2feaee57daafd AS build
ARG MODULE
ARG APP_VERSION=
WORKDIR /app

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

RUN case "$MODULE" in \
        backend|frontend|ingest) ;; \
        *) echo "MODULE must be backend, frontend or ingest (got '${MODULE}')" >&2; exit 1 ;; \
    esac; \
    ./gradlew ":${MODULE}:dependencies" --no-daemon

COPY ${MODULE}/src/main/ ${MODULE}/src/main/
COPY logging-support/src/main/ logging-support/src/main/
COPY frontend/oss-bundled-components.json frontend/oss-bundled-components.json

RUN ./gradlew ":${MODULE}:bootJar" ${APP_VERSION:+-PappVersion=$APP_VERSION} --no-daemon

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

FROM eclipse-temurin:25-jre-alpine@sha256:2ca9adf44f5c29d28ecd26cf92d75cc0c66b7f32bfd839a4439e363a8b428af8 AS runtime
WORKDIR /app

RUN apk upgrade --no-cache \
    && addgroup -S -g 10001 app \
    && adduser  -S -u 10001 -G app -H -h /nonexistent -s /sbin/nologin app \
    && mkdir -p /app/logs \
    && chown -R 10001:10001 /app

COPY --from=build --chown=10001:10001 /app/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /app/extracted/application/ ./

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

ENTRYPOINT ["java", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar"]

FROM runtime AS runtime-backend
EXPOSE 11261
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --start-interval=5s --retries=3 \
    CMD wget -q --no-check-certificate -O /dev/null https://localhost:11261/actuator/health/readiness || exit 1

FROM runtime AS runtime-frontend
EXPOSE 18081
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --start-interval=5s --retries=3 \
    CMD wget -q --no-check-certificate -O /dev/null https://localhost:18081/actuator/health/readiness || exit 1

FROM runtime AS runtime-ingest
EXPOSE 11262
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --start-interval=5s --retries=3 \
    CMD wget -q --no-check-certificate -O /dev/null https://localhost:11262/actuator/health/readiness || exit 1

# hadolint ignore=DL3006
FROM runtime-${MODULE}
