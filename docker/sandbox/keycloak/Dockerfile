FROM eclipse-temurin:25-jdk-alpine@sha256:c7c3d0bdf7f2bbc5a05a6712ed334d198360105634ea9adf76fca94f381b1e3f AS spi
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
COPY technical-support/build.gradle.kts technical-support/build-settings.properties technical-support/
RUN ./gradlew :keycloak-spi:dependencies --no-daemon --no-configuration-cache
COPY keycloak-spi/src/main/ keycloak-spi/src/main/
RUN set -eu; \
    ./gradlew :keycloak-spi:jar --no-daemon --no-configuration-cache; \
    set --; \
    for f in keycloak-spi/build/libs/keycloak-spi-*.jar; do \
        case "$f" in \
            *-plain.jar|*-sources.jar) continue ;; \
            *.jar) set -- "$@" "$f" ;; \
        esac; \
    done; \
    [ "$#" -eq 1 ] && [ -f "$1" ] || { echo "Expected exactly one keycloak-spi-*.jar, got $#: '$*'" >&2; exit 1; }; \
    cp "$1" /app/keycloak-spi.jar

FROM quay.io/keycloak/keycloak:26.8@sha256:723b1d6e254c0fc96d5cffec860c7e0744d6a3277822b865bb538cf891575e41
COPY --from=spi /app/keycloak-spi.jar /opt/keycloak/providers/keycloak-spi.jar
COPY keycloak-theme/krt-theme/ /opt/keycloak/themes/krt-theme/
COPY docker/sandbox/keycloak/realm-basetool.json /opt/keycloak/data/import/realm-basetool.json
COPY --chmod=755 docker/sandbox/keycloak/entrypoint.sh /opt/keycloak/bin/sandbox-entrypoint.sh
ENTRYPOINT ["/opt/keycloak/bin/sandbox-entrypoint.sh"]
CMD ["start-dev", "--import-realm", "--cache=local"]
