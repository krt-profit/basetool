### Security

- **Exchange-Gateway.** Das Gateway schickt Redis keine Lua-Skripte mehr: Byte-Budget,
  Schreibkontingente und Idempotenz-Sperren laufen als optimistische Transaktionen. Jeder
  Redis-Befehl läuft höchstens einmal, mit 2 s Zeitlimit; Abbrüche zählt eine neue Metrik mit Alarm.
  Vor dem Release wird die Redis-ACL neu gerendert und geladen (ADR-0389).
- **Redis-Monitoring.** Der Monitoring-Nutzer darf nur noch die Befehle des Exporters, kein
  `CLIENT TRACKING` mehr, über das sich Session-IDs mitlesen ließen; kein Dienst-Nutzer darf es.
