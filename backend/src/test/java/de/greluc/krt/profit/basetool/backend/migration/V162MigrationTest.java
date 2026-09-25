/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.migration;

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemKind;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * TestContainers-backed migration test for {@code
 * V162__manufacturer_uex_company_alias_and_dedup.sql}. Asserts the alias table ships with the
 * expected shape (PK on {@code uex_company_id}, FK to {@code manufacturer}, lookup index) and that
 * the one-time dedup SQL collapses two duplicate company rows of one brand onto the lowest-id
 * canonical row — repointing the child FK, carrying the SC Wiki / P4K links over, OR-ing the
 * surface flags and mapping both company ids in the alias table (ADR-0023 / REQ-DATA-004).
 *
 * <p>The migration itself runs once at boot against an empty schema (a no-op for the dedup), so the
 * dedup half of the file cannot be exercised by Flyway here. {@link
 * #dedupCollapsesDuplicateBrandOntoCanonicalRow()} seeds a duplicate pair and runs the file's
 * step-2/3 statements verbatim to prove the destructive merge behaves; everything it creates is
 * removed in {@link #cleanup()} so the shared test container is not polluted.
 */
@SpringBootTest
@ActiveProfiles("test")
class V162MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ManufacturerRepository manufacturerRepository;
  @Autowired private GameItemRepository gameItemRepository;

  private static final String TEST_ABBR = "TSTX162";
  private UUID seededGameItemId;

  @AfterEach
  void cleanup() {
    if (seededGameItemId != null) {
      jdbcTemplate.update("DELETE FROM game_item WHERE id = ?", seededGameItemId);
    }
    jdbcTemplate.update("DELETE FROM manufacturer WHERE abbreviation = ?", TEST_ABBR);
    jdbcTemplate.update(
        "DELETE FROM manufacturer_uex_company WHERE uex_company_id IN (9001, 9002)");
  }

  @Test
  void v162CreatesAliasTableWithExpectedShape() {
    Map<String, String> types = dataTypesOf("manufacturer_uex_company");
    assertEquals("integer", types.get("uex_company_id"));
    assertEquals("uuid", types.get("manufacturer_id"));

    Integer pk =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints WHERE table_name ="
                + " 'manufacturer_uex_company' AND constraint_type = 'PRIMARY KEY'",
            Integer.class);
    assertEquals(1, pk == null ? 0 : pk, "uex_company_id primary key must exist");

    Integer fk =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints WHERE table_name ="
                + " 'manufacturer_uex_company' AND constraint_type = 'FOREIGN KEY'",
            Integer.class);
    assertEquals(1, fk == null ? 0 : fk, "manufacturer_id foreign key must exist");

    Integer idx =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE tablename = 'manufacturer_uex_company' "
                + "AND indexname = 'idx_manufacturer_uex_company_manufacturer'",
            Integer.class);
    assertEquals(1, idx == null ? 0 : idx, "manufacturer_id lookup index must exist");
  }

  @Test
  void dedupCollapsesDuplicateBrandOntoCanonicalRow() {
    Manufacturer canonical = new Manufacturer();
    canonical.setName("Dedup Canonical 162");
    canonical.setAbbreviation(TEST_ABBR);
    canonical.setUexCompanyId(9001);
    canonical.setIsItemManufacturer(true);
    canonical.setIsVehicleManufacturer(false);
    canonical = manufacturerRepository.saveAndFlush(canonical);

    UUID scwikiUuid = UUID.fromString("a53bbc2b-0000-4000-8000-000000000162");
    UUID p4kUuid = UUID.fromString("a53bbc2b-0000-4000-8000-000000000163");
    Manufacturer duplicate = new Manufacturer();
    duplicate.setName("Dedup Duplicate 162");
    duplicate.setAbbreviation(TEST_ABBR);
    duplicate.setUexCompanyId(9002);
    duplicate.setIsItemManufacturer(false);
    duplicate.setIsVehicleManufacturer(true);
    duplicate.setScwikiUuid(scwikiUuid);
    duplicate.setScwikiCode(TEST_ABBR);
    duplicate.setP4kUuid(p4kUuid);
    duplicate = manufacturerRepository.saveAndFlush(duplicate);

    GameItem item = new GameItem();
    item.setName("Dedup Child Item 162");
    item.setKind(GameItemKind.GENERIC);
    item.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    item.setManufacturer(duplicate);
    item = gameItemRepository.saveAndFlush(item);
    seededGameItemId = item.getId();
    UUID canonicalId = canonical.getId();
    UUID duplicateId = duplicate.getId();

    jdbcTemplate.execute(DEDUP_SQL);

    assertFalse(
        manufacturerRepository.existsById(duplicateId), "the duplicate row must be deleted");
    assertTrue(manufacturerRepository.existsById(canonicalId), "the canonical row must survive");

    UUID childManufacturer =
        jdbcTemplate.queryForObject(
            "SELECT manufacturer_id FROM game_item WHERE id = ?", UUID.class, seededGameItemId);
    assertEquals(canonicalId, childManufacturer, "the child item must be repointed, not orphaned");

    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT is_item_manufacturer, is_vehicle_manufacturer, scwiki_uuid, p4k_uuid "
                + "FROM manufacturer WHERE id = ?",
            canonicalId);
    assertEquals(Boolean.TRUE, row.get("is_item_manufacturer"));
    assertEquals(Boolean.TRUE, row.get("is_vehicle_manufacturer"), "vehicle flag OR'd from loser");
    assertEquals(scwikiUuid, row.get("scwiki_uuid"), "Wiki link carried onto the canonical row");
    assertEquals(p4kUuid, row.get("p4k_uuid"), "P4K link carried onto the canonical row");

    assertEquals(canonicalId, aliasTarget(9001));
    assertEquals(canonicalId, aliasTarget(9002));
  }

  private UUID aliasTarget(int uexCompanyId) {
    return jdbcTemplate.queryForObject(
        "SELECT manufacturer_id FROM manufacturer_uex_company WHERE uex_company_id = ?",
        UUID.class,
        uexCompanyId);
  }

  private Map<String, String> dataTypesOf(String tableName) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ?",
            tableName);
    Map<String, String> out = new HashMap<>();
    for (Map<String, Object> r : rows) {
      out.put(((String) r.get("column_name")).toLowerCase(), (String) r.get("data_type"));
    }
    return out;
  }

  /**
   * The step-2/3 dedup statements from {@code V162__manufacturer_uex_company_alias_and_dedup.sql},
   * verbatim (the table + index already exist from the boot-time migration). Run as a single
   * multi-statement batch so the {@code ON COMMIT DROP} temp tables live for its duration. Keep in
   * sync with the migration file.
   */
  private static final String DEDUP_SQL =
      """
      DROP TABLE IF EXISTS _mfr_dedup;
      DROP TABLE IF EXISTS _mfr_xref;

      CREATE TEMP TABLE _mfr_dedup ON COMMIT DROP AS
      WITH grp AS (
          SELECT id,
                 uex_company_id,
                 lower(abbreviation)                                 AS abbr_key,
                 MIN(uex_company_id) OVER (PARTITION BY lower(abbreviation)) AS canon_uex_id,
                 COUNT(*)            OVER (PARTITION BY lower(abbreviation)) AS n
          FROM manufacturer
          WHERE uex_company_id IS NOT NULL
      )
      SELECT loser.id             AS loser_id,
             loser.uex_company_id AS loser_uex_id,
             canon.id             AS canonical_id
      FROM grp loser
      JOIN grp canon
        ON canon.abbr_key = loser.abbr_key
       AND canon.uex_company_id = loser.canon_uex_id
      WHERE loser.n > 1
        AND loser.uex_company_id <> loser.canon_uex_id;

      UPDATE ship_type st
         SET manufacturer_id = d.canonical_id
        FROM _mfr_dedup d
       WHERE st.manufacturer_id = d.loser_id;

      UPDATE game_item gi
         SET manufacturer_id = d.canonical_id
        FROM _mfr_dedup d
       WHERE gi.manufacturer_id = d.loser_id;

      CREATE TEMP TABLE _mfr_xref ON COMMIT DROP AS
      SELECT DISTINCT ON (d.canonical_id)
             d.canonical_id,
             m.scwiki_uuid,
             m.scwiki_code,
             m.scwiki_synced_at,
             m.p4k_uuid,
             m.p4k_synced_at
      FROM _mfr_dedup d
      JOIN manufacturer m ON m.id = d.loser_id
      WHERE m.scwiki_uuid IS NOT NULL
         OR m.p4k_uuid IS NOT NULL
      ORDER BY d.canonical_id, m.uex_company_id;

      UPDATE manufacturer m
         SET scwiki_uuid = NULL,
             p4k_uuid    = NULL
        FROM _mfr_dedup d
       WHERE m.id = d.loser_id;

      UPDATE manufacturer c
         SET scwiki_uuid      = COALESCE(c.scwiki_uuid, x.scwiki_uuid),
             scwiki_code      = COALESCE(c.scwiki_code, x.scwiki_code),
             scwiki_synced_at = COALESCE(c.scwiki_synced_at, x.scwiki_synced_at),
             p4k_uuid         = COALESCE(c.p4k_uuid, x.p4k_uuid),
             p4k_synced_at    = COALESCE(c.p4k_synced_at, x.p4k_synced_at)
        FROM _mfr_xref x
       WHERE c.id = x.canonical_id;

      UPDATE manufacturer c
         SET is_item_manufacturer =
                 COALESCE(c.is_item_manufacturer, FALSE) OR COALESCE(agg.item_any, FALSE),
             is_vehicle_manufacturer =
                 COALESCE(c.is_vehicle_manufacturer, FALSE) OR COALESCE(agg.veh_any, FALSE)
        FROM (
            SELECT d.canonical_id,
                   bool_or(COALESCE(m.is_item_manufacturer, FALSE))    AS item_any,
                   bool_or(COALESCE(m.is_vehicle_manufacturer, FALSE)) AS veh_any
            FROM _mfr_dedup d
            JOIN manufacturer m ON m.id = d.loser_id
            GROUP BY d.canonical_id
        ) agg
       WHERE c.id = agg.canonical_id;

      DELETE FROM manufacturer m
       USING _mfr_dedup d
       WHERE m.id = d.loser_id;

      INSERT INTO manufacturer_uex_company (uex_company_id, manufacturer_id)
      SELECT uex_company_id, id
      FROM manufacturer
      WHERE uex_company_id IS NOT NULL
      ON CONFLICT (uex_company_id) DO NOTHING;

      INSERT INTO manufacturer_uex_company (uex_company_id, manufacturer_id)
      SELECT loser_uex_id, canonical_id
      FROM _mfr_dedup
      ON CONFLICT (uex_company_id) DO UPDATE
         SET manufacturer_id = EXCLUDED.manufacturer_id;
      """;
}
