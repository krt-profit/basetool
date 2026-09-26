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

/**
 * TestContainers-backed migration test for {@code V110__create_game_item.sql}. Asserts the table
 * exists with every column and constraint listed in SC_WIKI_SYNC_PLAN.md §6.3.1 / R2 scope,
 * including the UNIQUE keys on {@code external_uuid} (R2-nullable) and {@code uex_item_id} plus the
 * CHECK on {@code source_systems} and {@code kind}.
 */
@SpringBootTest
@ActiveProfiles("test")
class V110MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v110CreatesGameItemTableWithExpectedColumns() {
    Map<String, String> types = dataTypesOf("game_item");
    assertEquals("uuid", types.get("id"));
    assertEquals("uuid", types.get("external_uuid"));
    assertEquals("character varying", types.get("name"));
    assertEquals("uuid", types.get("manufacturer_id"));
    assertEquals("character varying", types.get("kind"));
    assertEquals("character varying", types.get("source_systems"));

    assertEquals("integer", types.get("uex_item_id"));
    assertEquals("character varying", types.get("uex_slug"));
    assertEquals("integer", types.get("uex_category_id"));
    assertEquals("integer", types.get("uex_company_id"));
    assertEquals("integer", types.get("uex_vehicle_id"));
    assertEquals("uuid", types.get("linked_ship_type_id"));
    assertEquals("boolean", types.get("uex_is_commodity"));
    assertEquals("timestamp with time zone", types.get("uex_synced_at"));

    assertEquals("character varying", types.get("scwiki_slug"));
    assertEquals("character varying", types.get("classification"));
    assertEquals("double precision", types.get("mass"));
    assertEquals("double precision", types.get("dimension_x"));
    assertEquals("text", types.get("description_en"));
    assertEquals("timestamp with time zone", types.get("scwiki_synced_at"));
  }

  @Test
  void v110EnforcesUniqueExternalUuidAndUexItemId() {
    assertConstraintExists("uk_game_item_external_uuid");
    assertConstraintExists("uk_game_item_uex_item_id");
  }

  @Test
  void v110EnforcesSourceSystemsAndKindChecks() {
    Integer sourceCheck =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'game_item' "
                + "AND constraint_type = 'CHECK' "
                + "AND constraint_name = 'chk_game_item_source_systems'",
            Integer.class);
    assertEquals(1, sourceCheck == null ? 0 : sourceCheck, "source_systems CHECK must exist");

    Integer kindCheck =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'game_item' "
                + "AND constraint_type = 'CHECK' "
                + "AND constraint_name = 'chk_game_item_kind'",
            Integer.class);
    assertEquals(1, kindCheck == null ? 0 : kindCheck, "kind CHECK must exist");
  }

  @Test
  void v110CreatesIndexesForSyncLookupPaths() {
    assertIndexExists("idx_game_item_kind");
    assertIndexExists("idx_game_item_classification");
    assertIndexExists("idx_game_item_manufacturer");
    assertIndexExists("idx_game_item_uex_category");
    assertIndexExists("idx_game_item_linked_ship");
    assertIndexExists("idx_game_item_source_systems");
  }

  private void assertConstraintExists(String constraintName) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'game_item' AND constraint_name = ?",
            Integer.class,
            constraintName);
    assertEquals(1, count == null ? 0 : count, "constraint " + constraintName + " must exist");
  }

  private void assertIndexExists(String indexName) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE tablename = 'game_item' AND indexname = ?",
            Integer.class,
            indexName);
    assertEquals(1, count == null ? 0 : count, "index " + indexName + " must exist");
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
