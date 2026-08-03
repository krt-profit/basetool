-- =====================================================================
-- V228 - seed blueprint aliases for the German ammo-capacity suffix (#1485)
-- =====================================================================
-- Why: the SC Extractor now reads blueprint notifications from LOCALISED
-- Star Citizen clients. A German client renders item names from the German
-- global.ini, which keeps the base item name in English but translates the
-- parenthetical capacity unit:
--
--     S71 Rifle Magazine (30 cap)      <- English client
--     S71 Rifle Magazine (30 Schuss)   <- German client
--
-- `productName` is written byte-verbatim from the game log on purpose - it IS
-- the key the import resolves on - so the extractor must not fold the suffix
-- itself; that would invent a name the game never wrote. The mapping belongs
-- here, where the catalogue lives (#1485).
--
-- Nothing is broken without this seed: the extractor export carries no
-- structural `tag` (REQ-INV-019 is a no-op for it), the normalized exact match
-- misses, and `BlueprintFuzzyMatcher` catches these names at 0.80-0.87 against
-- a 0.5 threshold with the correct product at rank 1. The seed removes a
-- one-time manual pass of ~13 names that would otherwise land on exactly the
-- users the localisation support just enabled, and that reads like the tool
-- failing to recognise ordinary ammunition.
--
-- Derivation, not a hand-written list: the German spelling is produced from
-- OUR OWN catalogue by rewriting the suffix, so the seed cannot drift from a
-- hand-copied name and automatically covers every craftable `(N cap)` item
-- present at migration time - not only the 13 that happened to appear in the
-- issue's 424-log corpus. `product_key` is computed exactly as
-- `BlueprintNameNormalizer` does (trim, collapse whitespace, lowercase); the
-- ASCII-only names in this set make its quote-glyph folding irrelevant.
--
-- source_system is 'SCMDB' and NOT a new localisation-specific value:
-- `BlueprintImportService` hardcodes `SOURCE = BlueprintExternalAliasSource.SCMDB`
-- for its lookup, so any other value would produce rows the import never
-- queries. The name genuinely is an SCMDB-format log-export name - just the
-- German rendering of one.
--
-- ON CONFLICT DO NOTHING makes this idempotent and lets a user- or
-- admin-curated alias always win: the unique index
-- uq_blueprint_external_alias_source_lower_name (V176) is on the case-folded
-- name, so an existing row for the same spelling is never overwritten.
--
-- KNOWN LIMIT (deliberate): this is a one-shot seed. Ammo items added to the
-- catalogue by a later SC-Wiki sync are not covered and fall back to the fuzzy
-- matcher, which already resolves them at rank 1 - the same outcome as before
-- this migration, for one item instead of thirteen. Revisit only if that
-- becomes a recurring annoyance; a rerun of this statement is safe.
--
-- Rollback: DELETE FROM blueprint_external_alias
--            WHERE created_by = 'system' AND external_name LIKE '% Schuss)';

INSERT INTO blueprint_external_alias (id,
                                      source_system,
                                      external_name,
                                      product_key,
                                      product_name,
                                      output_item_id,
                                      created_by,
                                      note)
SELECT gen_random_uuid(),
       'SCMDB',
       regexp_replace(src.output_name, '\((\d+)\s+[Cc][Aa][Pp]\)', '(\1 Schuss)'),
       src.product_key,
       src.output_name,
       src.output_item_id,
       'system',
       'Auto-seeded (V228, #1485): German SC client renders the ammo-capacity suffix "(N cap)" as "(N Schuss)".'
  FROM (SELECT DISTINCT ON (LOWER(regexp_replace(BTRIM(b.output_name), '\s+', ' ', 'g')))
               LOWER(regexp_replace(BTRIM(b.output_name), '\s+', ' ', 'g')) AS product_key,
               BTRIM(b.output_name)                                        AS output_name,
               b.output_item_id                                            AS output_item_id
          FROM blueprint b
         WHERE b.scwiki_deleted_at IS NULL
           AND b.output_name IS NOT NULL
           AND b.output_name ~ '\(\d+\s+[Cc][Aa][Pp]\)'
         -- Mirrors BlueprintProductService.buildProductMap: one row per normalized product key,
         -- preferring a variant that actually carries an output item so the alias snapshot can
         -- still resolve if the product is later renamed away from the master catalogue.
         ORDER BY LOWER(regexp_replace(BTRIM(b.output_name), '\s+', ' ', 'g')),
                  (b.output_item_id IS NULL),
                  b.scwiki_key,
                  b.id) src
ON CONFLICT DO NOTHING;
