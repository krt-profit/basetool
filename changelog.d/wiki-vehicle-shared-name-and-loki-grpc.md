### Fixed

- The SC Wiki vehicle sync no longer tries to insert a second ship type for a Wiki variant that shares its name with a stored one (e.g. "Hammerhead" GS, "Polaris", "Skylark"); it reports an identity conflict instead of failing with duplicate-key errors in Postgres.
- The Grafana "App errors & warnings" panel loads again: Loki accepts query results up to 64 MiB, so a range holding stack traces no longer times out with an empty panel.
