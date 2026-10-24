### Changed

- **Neue Anmeldung einmalig.** Der Keycloak-Realm heißt jetzt `basetool` statt `iri`; alle melden sich einmal neu an, externe Austausch-Clients stellen auf den neuen Issuer `https://profit-base.online/auth/realms/basetool` um. Die Daten der Staffeln bleiben unverändert.
- **Umgebungsvariablen `BASETOOL_*`.** Aus `IRI_<NAME>` wird `BASETOOL_<NAME>` ohne Übergangszeit (`IRI_BASETOOL_VERSION` wird `BASETOOL_VERSION`); lokale `.env`-Dateien umstellen mit `sed -i 's/^IRI_BASETOOL_VERSION=/BASETOOL_VERSION=/; s/^IRI_/BASETOOL_/' .env`. Die Sandbox-/E2E-Realmdatei heißt jetzt `docker/sandbox/keycloak/realm-basetool.json`.
- **Host-Layout für Betreiber.** Der Host nutzt `/opt/basetool`, `/etc/basetool`, `/var/lib/basetool`, `/var/log/basetool` und `/var/cache/basetool`; die Umgebungsdatei `/etc/basetool/basetool.env` liegt außerhalb des Release-Baums. Ablauf der Umstellung: `docs/HOST_LAYOUT_MIGRATION_RUNBOOK.md`.
