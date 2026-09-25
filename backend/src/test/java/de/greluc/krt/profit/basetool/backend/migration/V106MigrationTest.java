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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * TestContainers-backed migration test for {@code V106__add_scwiki_columns_to_material.sql}.
 * Asserts that every column declared by the migration is present on the {@code material} table with
 * the right defaults — specifically that {@code is_visible} and {@code source_systems} carry the
 * post-migration backfill defaults that preserve existing UEX-sourced catalogue visibility
 * (SC_WIKI_SYNC_AGENT_PROMPT.md §5 pitfall #5).
 */
@SpringBootTest
@ActiveProfiles("test")
class V106MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v106AddsScwikiColumnsAndDefaultsToMaterial() {
    Map<String, ColumnInfo> columns = columnsOf("material");

    assertColumnPresent(columns, "scwiki_uuid", "uuid");
    assertColumnPresent(columns, "scwiki_key", "character varying");
    assertColumnPresent(columns, "scwiki_slug", "character varying");
    assertColumnPresent(columns, "scwiki_synced_at", "timestamp with time zone");
    assertColumnPresent(columns, "scwiki_deleted_at", "timestamp with time zone");
    assertColumnPresent(columns, "density_g_per_cc", "double precision");
    assertColumnPresent(columns, "instability", "double precision");
    assertColumnPresent(columns, "resistance", "double precision");

    ColumnInfo isVisible = columns.get("is_visible");
    assertNotNull(isVisible, "is_visible column must exist");
    assertEquals("NO", isVisible.isNullable, "is_visible must be NOT NULL");
    assertTrue(
        isVisible.columnDefault != null && isVisible.columnDefault.toLowerCase().contains("true"),
        "is_visible must default to TRUE to preserve existing catalogue visibility: "
            + isVisible.columnDefault);

    ColumnInfo sourceSystems = columns.get("source_systems");
    assertNotNull(sourceSystems, "source_systems column must exist");
    assertEquals("NO", sourceSystems.isNullable, "source_systems must be NOT NULL");
    assertTrue(
        sourceSystems.columnDefault != null && sourceSystems.columnDefault.contains("UEX_ONLY"),
        "source_systems must default to 'UEX_ONLY' so the post-migration backfill matches the"
            + " pre-Wiki catalogue state: "
            + sourceSystems.columnDefault);
  }

  @Test
  void v106AddsUniqueConstraintOnScwikiUuid() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'material' "
                + "AND constraint_type = 'UNIQUE' "
                + "AND constraint_name = 'uk_material_scwiki_uuid'",
            Integer.class);
    assertEquals(
        1,
        count == null ? 0 : count,
        "UNIQUE constraint uk_material_scwiki_uuid must exist on material(scwiki_uuid)");
  }

  private Map<String, ColumnInfo> columnsOf(String tableName) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT column_name, data_type, is_nullable, column_default "
                + "FROM information_schema.columns WHERE table_name = ?",
            tableName);
    java.util.Map<String, ColumnInfo> map = new java.util.HashMap<>();
    for (Map<String, Object> row : rows) {
      String name = ((String) row.get("column_name")).toLowerCase();
      map.put(
          name,
          new ColumnInfo(
              (String) row.get("data_type"),
              (String) row.get("is_nullable"),
              (String) row.get("column_default")));
    }
    return map;
  }

  private static void assertColumnPresent(
      Map<String, ColumnInfo> columns, String name, String expectedDataType) {
    ColumnInfo column = columns.get(name);
    assertNotNull(column, "column " + name + " must exist");
    assertEquals(expectedDataType, column.dataType, "data_type for column " + name);
  }

  /**
   * Minimal {@code information_schema.columns} projection used by the migration assertions. Kept as
   * a record so the test code reads top-down without intermediate {@code Map.get} chains.
   *
   * @param dataType {@code information_schema.columns.data_type}
   * @param isNullable {@code "YES"} / {@code "NO"}
   * @param columnDefault raw default expression, or {@code null}
   */
  private record ColumnInfo(String dataType, String isNullable, String columnDefault) {}
}
