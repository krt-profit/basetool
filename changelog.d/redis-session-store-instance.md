### Security

- **Redis getrennt.** Die Sessions liegen in einer eigenen Redis-Instanz (`redis-session`), die nur
  das Frontend erreicht; Exchange, Ingest-Übergaben und Live-Sync laufen über `redis-shared`. Der
  Umzug braucht drei neue Passwörter (`REDIS_SESSION_*`) und zwei gerenderte ACL-Dateien; niemand
  wird abgemeldet (ADR-0388).
