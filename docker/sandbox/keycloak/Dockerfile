FROM eclipse-temurin:25-jdk-alpine@sha256:425f0b733cb23843bd78dbe5552513976d0cf12b9722752c48a5161cf5cd2f32 AS spi
WORKDIR /app
COPY --chmod=755 gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts gradle.properties lombok.config ./
COPY build-logic/ build-logic/
COPY backend/build.gradle.kts backend/build-settings.properties backend/
COPY frontend/build.gradle.kts frontend/build-settings.properties frontend/
COPY ingest/build.gradle.kts ingest/build-settings.properties ingest/
COPY keycloak-spi/build.gradle.kts keycloak-spi/build-settings.properties keycloak-spi/lombok.config keycloak-spi/
COPY test-support/build.gradle.kts test-support/build-settings.properties test-support/
COPY logging-support/build.gradle.kts logging-support/build-settings.properties logging-support/
RUN ./gradlew :keycloak-spi:dependencies --no-daemon --no-configuration-cache
COPY keycloak-spi/src/main/ keycloak-spi/src/main/
RUN set -eu; \
    ./gradlew :keycloak-spi:jar --no-daemon --no-configuration-cache; \
    matches=""; \
    for f in keycloak-spi/build/libs/keycloak-spi-*.jar; do \
        case "$f" in \
            *-plain.jar|*-sources.jar) continue ;; \
            *.jar) matches="$matches $f" ;; \
        esac; \
    done; \
    set -- $matches; \
    [ "$#" -eq 1 ] && [ -f "$1" ] || { echo "Expected exactly one keycloak-spi-*.jar, got $#: '$*'" >&2; exit 1; }; \
    cp "$1" /app/keycloak-spi.jar

FROM quay.io/keycloak/keycloak:26.8@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b
COPY --from=spi /app/keycloak-spi.jar /opt/keycloak/providers/keycloak-spi.jar
COPY keycloak-theme/krt-theme/ /opt/keycloak/themes/krt-theme/
COPY docker/sandbox/keycloak/realm-basetool.json /opt/keycloak/data/import/realm-basetool.json
COPY --chmod=755 docker/sandbox/keycloak/entrypoint.sh /opt/keycloak/bin/sandbox-entrypoint.sh
ENTRYPOINT ["/opt/keycloak/bin/sandbox-entrypoint.sh"]
CMD ["start-dev", "--import-realm", "--cache=local"]
