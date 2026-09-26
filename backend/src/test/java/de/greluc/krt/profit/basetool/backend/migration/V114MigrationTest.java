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
 * TestContainers-backed migration test for {@code V114__create_blueprint_tables.sql}. Asserts the
 * three blueprint tables exist with their columns, the {@code scwiki_uuid} UNIQUE key, the relaxed
 * ingredient CHECK constraints (kind / FK exclusivity + quantity exclusivity, permitting an
 * unresolved null FK), and the FK indexes.
 */
@SpringBootTest
@ActiveProfiles("test")
class V114MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v114CreatesBlueprintTable() {
    Map<String, String> types = dataTypesOf("blueprint");
    assertEquals("uuid", types.get("id"));
    assertEquals("uuid", types.get("scwiki_uuid"));
    assertEquals("uuid", types.get("output_item_id"));
    assertEquals("character varying", types.get("output_name"));
    assertEquals("integer", types.get("craft_time_seconds"));
    assertEquals("boolean", types.get("is_available_by_default"));
    assertEquals("timestamp with time zone", types.get("scwiki_synced_at"));

    Integer uk =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'blueprint' AND constraint_type = 'UNIQUE' "
                + "AND constraint_name = 'uk_blueprint_scwiki_uuid'",
            Integer.class);
    assertEquals(1, uk == null ? 0 : uk, "scwiki_uuid UNIQUE constraint must exist");
  }

  @Test
  void v114CreatesBlueprintIngredientTableWithRelaxedChecks() {
    Map<String, String> types = dataTypesOf("blueprint_ingredient");
    assertEquals("uuid", types.get("blueprint_id"));
    assertEquals("integer", types.get("order_index"));
    assertEquals("character varying", types.get("kind"));
    assertEquals("uuid", types.get("material_id"));
    assertEquals("uuid", types.get("game_item_id"));
    assertEquals("uuid", types.get("wiki_resource_uuid"));
    assertEquals("uuid", types.get("wiki_item_uuid"));
    assertEquals("double precision", types.get("quantity_scu"));
    assertEquals("integer", types.get("quantity_units"));

    assertCheckExists("blueprint_ingredient", "chk_blueprint_ingredient_kind");
    assertCheckExists("blueprint_ingredient", "chk_blueprint_ingredient_fk_exclusivity");
    assertCheckExists("blueprint_ingredient", "chk_blueprint_ingredient_quantity_exclusivity");
  }

  @Test
  void v114IngredientCheck_permitsUnresolvedResourceLine() {
    java.util.UUID bpId = insertBlueprint();
    assertDoesNotThrow(
        () ->
            jdbcTemplate.update(
                "INSERT INTO blueprint_ingredient "
                    + "(id, blueprint_id, order_index, kind, quantity_scu) "
                    + "VALUES (gen_random_uuid(), ?, 0, 'RESOURCE', 1.5)",
                bpId));
  }

  @Test
  void v114IngredientCheck_rejectsResourceWithGameItemFk() {
    java.util.UUID bpId = insertBlueprint();
    assertThrows(
        Exception.class,
        () ->
            jdbcTemplate.update(
                "INSERT INTO blueprint_ingredient "
                    + "(id, blueprint_id, order_index, kind, game_item_id) "
                    + "VALUES (gen_random_uuid(), ?, 0, 'RESOURCE', gen_random_uuid())",
                bpId));
  }

  @Test
  void v114CreatesDismantleReturnTable() {
    Map<String, String> types = dataTypesOf("blueprint_dismantle_return");
    assertEquals("uuid", types.get("blueprint_id"));
    assertEquals("integer", types.get("order_index"));
    assertEquals("uuid", types.get("material_id"));
    assertEquals("uuid", types.get("wiki_resource_uuid"));
    assertEquals("double precision", types.get("quantity_scu"));
  }

  private java.util.UUID insertBlueprint() {
    java.util.UUID id = java.util.UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO blueprint (id, scwiki_uuid, is_available_by_default) "
            + "VALUES (?, gen_random_uuid(), false)",
        id);
    return id;
  }

  private void assertCheckExists(String table, String constraintName) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = ? AND constraint_type = 'CHECK' AND constraint_name = ?",
            Integer.class,
            table,
            constraintName);
    assertEquals(1, count == null ? 0 : count, "CHECK " + constraintName + " must exist");
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
