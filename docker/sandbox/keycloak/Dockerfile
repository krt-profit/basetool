FROM eclipse-temurin:25-jdk-alpine@sha256:3fd2d245c4e0eba615fe366a71b8bd25f5db7104f53e4026b24bf508b880bd2a AS spi
WORKDIR /app
COPY --chmod=755 gradlew .
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts gradle.properties lombok.config ./
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

FROM quay.io/keycloak/keycloak:26.8@sha256:b0f60d489d51c5d113390bdf5461d4c06e6051be026c05549f2e1e10ec352bcc
COPY --from=spi /app/keycloak-spi.jar /opt/keycloak/providers/keycloak-spi.jar
COPY keycloak-theme/krt-theme/ /opt/keycloak/themes/krt-theme/
COPY docker/sandbox/keycloak/realm-iri.json /opt/keycloak/data/import/realm-iri.json
COPY --chmod=755 docker/sandbox/keycloak/entrypoint.sh /opt/keycloak/bin/sandbox-entrypoint.sh
ENTRYPOINT ["/opt/keycloak/bin/sandbox-entrypoint.sh"]
CMD ["start-dev", "--import-realm", "--cache=local"]
