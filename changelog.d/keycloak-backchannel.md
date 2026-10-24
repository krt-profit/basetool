### Added

- **Interner Keycloak-Rückkanal.** Mit `IRI_KEYCLOAK_BACKCHANNEL_URI` holt das Frontend Token,
  Schlüssel und Benutzerdaten direkt über die interne Keycloak-Verbindung statt über die öffentliche
  Edge; Anmeldung und Token-Erneuerung hängen dann nicht mehr an der Edge. Ohne den Wert bleibt
  alles wie bisher (REQ-SEC-087).
