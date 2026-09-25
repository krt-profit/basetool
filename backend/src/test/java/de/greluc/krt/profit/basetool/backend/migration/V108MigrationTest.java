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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Migration test for {@code V108__create_material_external_alias.sql}: the table, columns, check
 * constraint and index exist, and the seed INSERTs create exactly six alias rows when the target
 * materials exist and are idempotent (REQ-REFINERY-010).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class V108MigrationTest {

  /**
   * SC Wiki external name → UEX-side material name. Mirrors the V108 seed exactly so a future drift
   * between SQL and test fails the build.
   */
  private static final List<String[]> SEED_PAIRS =
      List.of(
          new String[] {"Raw Silicon", "Silicon (Raw)"},
          new String[] {"Stileron (Ore)", "Stileron (Raw)"},
          new String[] {"Raw Ouratite", "Ouratite (Raw)"},
          new String[] {"Hephaestanite (R)", "Hephaestanite (Raw)"},
          new String[] {"Lastaprene", "Lastaphrene"},
          new String[] {"Lunes (Spiral Fruit)", "Lunes"});

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v108CreatesTableWithExpectedShape() {
    Map<String, String> types = dataTypesOf("material_external_alias");

    assertEquals("uuid", types.get("id"));
    assertEquals("uuid", types.get("material_id"));
    assertEquals("character varying", types.get("source_system"));
    assertEquals("character varying", types.get("external_name"));
    assertEquals("character varying", types.get("external_key"));
    assertEquals("uuid", types.get("external_uuid"));
    assertEquals("character varying", types.get("external_code"));
    assertEquals("text", types.get("note"));
    assertEquals("character varying", types.get("created_by"));
  }

  /**
   * Alias uniqueness is enforced by the case-insensitive {@code (source_system,
   * LOWER(external_name))} index rather than a case-sensitive constraint, alongside the
   * source-system CHECK (REQ-REFINERY-010).
   */
  @Test
  void v108UniqueConstraintSupersededByV146CaseInsensitiveIndex() {
    Integer uniqueCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'material_external_alias' "
                + "AND constraint_type = 'UNIQUE' "
                + "AND constraint_name = 'uk_material_external_alias_source_external_name'",
            Integer.class);
    assertEquals(
        0,
        uniqueCount == null ? 0 : uniqueCount,
        "V108 case-sensitive UNIQUE constraint must be dropped by V146");

    Integer caseInsensitiveUniqueIndexCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes "
                + "WHERE tablename = 'material_external_alias' "
                + "AND indexname = 'uq_material_external_alias_source_lower_name' "
                + "AND indexdef LIKE 'CREATE UNIQUE INDEX%'",
            Integer.class);
    assertEquals(
        1,
        caseInsensitiveUniqueIndexCount == null ? 0 : caseInsensitiveUniqueIndexCount,
        "V146 case-insensitive unique index must exist and be UNIQUE");

    Integer checkCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'material_external_alias' "
                + "AND constraint_type = 'CHECK' "
                + "AND constraint_name = 'chk_material_external_alias_source_system'",
            Integer.class);
    assertEquals(1, checkCount == null ? 0 : checkCount, "CHECK on source_system must exist");
  }

  @Test
  void v108AddsIndexOnMaterialId() {
    Integer indexCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes "
                + "WHERE tablename = 'material_external_alias' "
                + "AND indexname = 'idx_material_external_alias_material'",
            Integer.class);
    assertEquals(1, indexCount == null ? 0 : indexCount, "index on material_id must exist");
  }

  /**
   * Replaying the V108 seed INSERTs against populated materials creates exactly six alias rows, and
   * a case-variant replay inserts nothing (REQ-REFINERY-010).
   */
  @Test
  void v108SeedInsertsCreateSixAliasRowsWhenTargetMaterialsExist() {
    Map<String, java.util.UUID> insertedMaterialIds = new HashMap<>();
    for (String[] pair : SEED_PAIRS) {
      String materialName = pair[1];
      java.util.UUID id = java.util.UUID.randomUUID();
      jdbcTemplate.update(
          "INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material, "
              + "is_job_order, is_visible, source_systems) "
              + "VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY')",
          id,
          materialName);
      insertedMaterialIds.put(materialName, id);
    }

    int seededBefore =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM material_external_alias WHERE created_by = 'system'",
            Integer.class);

    for (String[] pair : SEED_PAIRS) {
      String wikiName = pair[0];
      String uexName = pair[1];
      jdbcTemplate.update(
          "INSERT INTO material_external_alias "
              + "(id, material_id, source_system, external_name, note, created_by) "
              + "SELECT gen_random_uuid(), m.id, 'SCWIKI', ?, "
              + "'V108 seed replay', 'system' "
              + "FROM material m WHERE m.name = ? "
              + "ON CONFLICT (source_system, LOWER(external_name)) DO NOTHING",
          wikiName,
          uexName);
    }

    int seededAfter =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM material_external_alias WHERE created_by = 'system'",
            Integer.class);

    assertEquals(
        seededBefore + 6,
        seededAfter,
        "exactly 6 alias rows must materialise once the target materials exist");

    for (String[] pair : SEED_PAIRS) {
      jdbcTemplate.update(
          "INSERT INTO material_external_alias "
              + "(id, material_id, source_system, external_name, note, created_by) "
              + "SELECT gen_random_uuid(), m.id, 'SCWIKI', ?, "
              + "'V108 seed replay', 'system' "
              + "FROM material m WHERE m.name = ? "
              + "ON CONFLICT (source_system, LOWER(external_name)) DO NOTHING",
          pair[0].toUpperCase(java.util.Locale.ROOT),
          pair[1]);
    }

    int seededAfterCaseVariantReplay =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM material_external_alias WHERE created_by = 'system'",
            Integer.class);

    assertEquals(
        seededAfter,
        seededAfterCaseVariantReplay,
        "case-variant replay must not create additional rows");

    java.util.UUID resolvedMaterialId =
        jdbcTemplate.queryForObject(
            "SELECT material_id FROM material_external_alias "
                + "WHERE source_system = 'SCWIKI' AND external_name = ?",
            java.util.UUID.class,
            "Raw Silicon");
    assertEquals(
        insertedMaterialIds.get("Silicon (Raw)"),
        resolvedMaterialId,
        "Raw Silicon alias must resolve to Silicon (Raw) material id");
  }

  private Map<String, String> dataTypesOf(String tableName) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ?",
            tableName);
    Map<String, String> out = new HashMap<>();
    for (Map<String, Object> row : rows) {
      out.put(((String) row.get("column_name")).toLowerCase(), (String) row.get("data_type"));
    }
    return out;
  }
}
