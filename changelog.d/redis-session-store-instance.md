### Security

- **Redis getrennt.** Die Sessions liegen in einer eigenen Redis-Instanz (`redis-session`), die nur
  das Frontend erreicht; Exchange, Ingest-Übergaben und Live-Sync laufen über `redis-shared`. Der
  Umzug braucht drei neue Passwörter (`REDIS_SESSION_*`) und zwei gerenderte ACL-Dateien unter
  `/etc/basetool/redis/`; niemand wird abgemeldet (ADR-0388).
- **Redis-ACL gehärtet.** `render-redis-acl.py` verweigert ein ungesetztes `REDIS_DEFAULT_USER`, ein
  Passwort unter zwei Variablen und Rechte außerhalb der eigenen Instanz; `check-conformance.py`
  prüft neu, dass `default` aus ist und nur Frontend und Exporter am Session-Netz hängen. Der
  Registry-Abgleich schreibt die Sperrlisten vor der Registry.
