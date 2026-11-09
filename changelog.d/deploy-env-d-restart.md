### Fixed

- **Deployment.** Ändert ein Release nur die gerenderte Umgebungsdatei eines Dienstes
  (`env.d/<dienst>.env`), startet `deploy.sh` diesen Dienst jetzt neu – App-Dienste im geprüften
  Neustartfenster, Monitoring-Dienste beim Monitoring-Abgleich. Bisher lief der Container mit der
  alten Umgebung weiter, bis ihn jemand von Hand neu startete.
