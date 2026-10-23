### Fixed

- **Monitoring.** Das Dashboard *SSH / host auth* zeigt fehlgeschlagene und erfolgreiche Anmeldungen
  sowie sudo-Fehler wieder an statt „No data“. Neu sind die Erkennungen und Sperren von fail2ban und
  die häufigsten auffälligen Adressen. `SshFailedAuthSpike` und `SudoAuthFailure` erkennen jetzt die
  Logzeilen, die die Rocky-Server tatsächlich schreiben.
