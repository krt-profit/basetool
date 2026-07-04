# Profit Basetool — host configuration bundle image.
#
# A `FROM scratch` artifact whose ONLY purpose is to ship the host-side runtime
# configuration the production deploy consumes: the compose file plus the two
# bind-mounted asset trees (the NPM maintenance page and the Keycloak login
# theme). It carries no executable layer and no base image — the server never
# *runs* it. `scripts/deploy.sh` extracts `/config` out of it with
# `docker create` + `docker cp` and copies the allowlisted paths into
# /var/iri/code (see docs/deployment.md and docs/adr/0049-*).
#
# Why an image and not an oras artifact: it rides the EXACT channel the app
# images already use — built by release-images.yml, signed by cosign keyless,
# promoted to :stable by promote.yml, resolved by `docker buildx imagetools`,
# pulled with the read-only GHCR token — so the host needs no new tool and no
# new credential.
#
# The COPY list is an explicit ALLOWLIST: host secrets (.env, keystore.p12,
# realm-export.json, keycloak/providers, the systemd units and deploy.sh itself)
# are never named here. .dockerignore additionally bars secrets from the build
# context, release-images.yml asserts the built bundle carries none, and
# deploy.sh re-asserts their absence before applying. deploy.sh and the systemd
# units are deliberately left OUT so a promoted bundle can never overwrite the
# running deployer (self-update hazard) — they stay a manual bootstrap concern.
#
# The monitoring compose + config tree (epic #936, ADR-0072) ride the same channel so a promoted
# release ships updated dashboards/alert rules to the host. They carry NO secrets (every credential
# is a host-side file under /var/iri/monitoring/secrets/ or an .env value); deploy.sh's
# assert_no_secrets guard still runs over the whole bundle.
FROM scratch

COPY docker-compose.yml /config/docker-compose.yml
COPY docker-compose.monitoring.yml /config/docker-compose.monitoring.yml
COPY docker/maintenance /config/docker/maintenance
COPY keycloak-theme /config/keycloak-theme
COPY monitoring /config/monitoring
