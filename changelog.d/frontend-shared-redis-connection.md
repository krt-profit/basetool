### Changed

- **Frontend, Redis.** Live-Sync und Ingest-Handoff laufen über eine eigene Redis-Verbindung
  (`REDIS_SHARED_*`), die Sessions über die bisherige. Noch zeigen beide auf dieselbe Instanz;
  das bereitet den getrennten Session-Store vor (ADR-0388). Ist diese Redis beim Start nicht
  erreichbar, startet das Frontend trotzdem; Live-Sync verbindet sich nach.
