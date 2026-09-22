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
# realm-export.json, keycloak/providers and deploy.sh itself) are never named
# here. .dockerignore additionally bars secrets from the build context,
# release-images.yml asserts the built bundle carries none, and deploy.sh
# re-asserts their absence before applying. deploy.sh and the four OPERATIONAL
# units (iri-deploy, iri-backup, iri-restore-drill, iri-container-cleanup) are
# deliberately left OUT so a promoted bundle can never overwrite the running
# deployer — a deployer that updates itself mid-run is a self-update hazard.
# Those are provisioning, and ansible/roles/basetool_host delivers them.
#
# The QUADLET units are the opposite case and the distinction is the whole point:
# they are the deployment. Under Compose the deployment is docker-compose.yml,
# which has ridden this bundle since ADR-0049; under Quadlet the same definition
# IS the unit files, so leaving them out would mean a release could change an
# image, a health command, a memory limit or an environment variable and have no
# way to reach the host. ADR-0188 puts unit files in the delivery column for
# exactly this reason. Until 2026-09-18 nothing implemented it: the units were
# generated, drift-checked in CI, committed -- and went nowhere, while
# docs/archive/PODMAN_HOST_BOOTSTRAP.md already described this bundle as carrying them.
#
# The monitoring compose + config tree (epic #936, ADR-0072) ride the same channel so a promoted
# release ships updated dashboards/alert rules to the host. They carry NO secrets (every credential
# is a host-side file under /var/iri/monitoring/secrets/ or an .env value); deploy.sh's
# assert_no_secrets guard still runs over the whole bundle.
FROM scratch

COPY docker-compose.yml /config/docker-compose.yml
COPY docker-compose.monitoring.yml /config/docker-compose.monitoring.yml
COPY docker/maintenance /config/docker/maintenance
# The edge proxy's whole configuration (ADR-0162). Without this line the compose
# file references ./docker/edge/... on a host that has never seen it, and the
# edge starts against an empty mount — the exact "it shipped and did nothing"
# shape this bundle exists to prevent.
COPY docker/edge /config/docker/edge
# The ACME renewal loop, for the same reason and with a sharper edge. It lived inside the compose
# file as a block scalar until 2026-09-16 and moved to docker/acme/publish-loop.sh then; the
# allowlist was not extended with it, so from that commit on both the compose service and the
# Quadlet unit have mounted a directory the bundle does not carry. On a host that already had the
# container it kept running with the command baked into it and nothing showed. On a fresh one --
# the Podman cutover host, 2026-09-22 -- it refused to start at all:
#
#     Error: statfs /var/iri/code/docker/acme: no such file or directory
#
# and with it went every certificate renewal on that host. deploy.sh mirrors each subtree by name,
# so it needs the matching entry too; the comment above its edge mirror says so in as many words.
COPY docker/acme /config/docker/acme
COPY keycloak-theme /config/keycloak-theme
COPY monitoring /config/monitoring
# The Quadlet deployment: 18 .container units, 18 .network units, 3 .volume units, and the
# env.d templates beside them. The templates carry `KEY=${VAR:?}` PLACEHOLDERS and no values --
# deploy.sh renders them on the host against the host's own .env, which never leaves it.
COPY quadlet /config/quadlet
