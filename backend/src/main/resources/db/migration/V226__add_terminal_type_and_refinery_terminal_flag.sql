-- Refinery locations are derived from the refinery TERMINAL, not from UEX's parent-level
-- has_refinery flag (REQ-REFINERY-020).
--
-- Why: UEX publishes two independent statements about "is there a refinery here" and they disagree.
--   * `/space_stations`, `/cities`, `/outposts` carry a `has_refinery` boolean on the PARENT.
--   * `/terminals` carries one row per kiosk with `type = 'refinery'` — the same record the UEX
--     site itself renders its refinery list from.
-- Measured against the live UEX API on 2026-07-28: 21 terminals have `type = 'refinery'`, but the
-- parent flag is wrong in BOTH directions --
--   * false negatives (parent flag 0, refinery terminal present): MIC-L5 Modern Icarus Station
--     (terminal 244), ARC-L4 Faint Glen Station (terminal 250), Patch City (terminal 433) — these
--     were silently absent from the refinery-order picker even though members can refine there;
--   * false positives (parent flag 1, no refinery terminal at all): People's Service Station
--     Alpha / Delta / Lambda / Theta — offered as refineries that do not exist in-game.
--
-- Two columns, deliberately kept apart:
--   * `terminal.type` mirrors the upstream discriminator verbatim (item / commodity /
--     commodity_raw / fuel / refinery / vehicle_buy / vehicle_rent).
--   * `city.has_refinery_terminal` and `space_station.has_refinery_terminal` hold the DERIVED
--     truth, recomputed on every sweep by UexUniverseSyncService.reconcileRefineryTerminalFlags().
--     `has_refinery` keeps UEX's raw claim untouched for diagnostics — the same "raw upstream value
--     next to the effective value" split already used by terminal.uex_has_loading_dock (V186).
--
-- Storing the derived value rather than resolving it per read keeps the refinery-order create /
-- update gate free of any DB query: it reads the flag off the Location's already-loaded parent, as
-- it always has. That matters — issuing a query midway through updateRefineryOrder would auto-flush
-- the transaction and make the goods clear()/re-add race its own freshly written rows.
--
-- No backfill: `type` is not derivable from local data, so both new columns start empty/false and
-- the next universe sweep (hourly) populates them. Until then the picker matches nothing new — it
-- degrades to a shorter list, never to a wrong one. `type` is a non-reserved word in Postgres and
-- needs no quoting; VARCHAR(255) matches the other UEX-mirrored string columns on `terminal` (V1).

ALTER TABLE terminal
    ADD COLUMN type VARCHAR(255);

ALTER TABLE city
    ADD COLUMN has_refinery_terminal BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE space_station
    ADD COLUMN has_refinery_terminal BOOLEAN NOT NULL DEFAULT FALSE;

-- Backs the reconciliation's per-parent existence probe. Stays tiny: 21 of ~823 terminal rows
-- qualify.
CREATE INDEX idx_terminal_refinery_parent
    ON terminal (space_station_name, city_name)
    WHERE type = 'refinery';
