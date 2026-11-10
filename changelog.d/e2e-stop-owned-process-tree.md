### Fixed

- E2E harness: a timed-out or interrupted `docker compose` step now stops the whole process tree it started before retrying, so two builds no longer compete for the Docker VM; every attempt writes its own log under `frontend/build/e2e/`.
