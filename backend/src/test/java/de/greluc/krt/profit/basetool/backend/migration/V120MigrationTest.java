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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Migration test for {@code V120__add_blueprint_requirement_groups.sql}: the requirement-group
 * tables and blueprint column additions exist and validate against the entities.
 */
@SpringBootTest
@ActiveProfiles("test")
class V120MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v120CreatesRequirementGroupTables() {
    Map<String, String> group = dataTypesOf("blueprint_requirement_group");
    assertEquals("uuid", group.get("id"));
    assertEquals("uuid", group.get("blueprint_id"));
    assertEquals("integer", group.get("order_index"));
    assertEquals("character varying", group.get("group_key"));
    assertEquals("character varying", group.get("name"));
    assertEquals("integer", group.get("required_count"));

    Map<String, String> modifier = dataTypesOf("blueprint_requirement_modifier");
    assertEquals("uuid", modifier.get("requirement_group_id"));
    assertEquals("integer", modifier.get("order_index"));
    assertEquals("character varying", modifier.get("property_key"));
    assertEquals("character varying", modifier.get("better_when"));
    assertEquals("double precision", modifier.get("quality_min"));
    assertEquals("double precision", modifier.get("modifier_at_min_quality"));
    assertEquals("double precision", modifier.get("modifier_at_max_quality"));

    Map<String, String> summary = dataTypesOf("blueprint_summary_property");
    assertEquals("uuid", summary.get("blueprint_id"));
    assertEquals("integer", summary.get("order_index"));
    assertEquals("character varying", summary.get("property_key"));
    assertEquals("character varying", summary.get("better_when"));
  }

  @Test
  void v120AddsBlueprintAndIngredientColumns() {
    Map<String, String> blueprint = dataTypesOf("blueprint");
    assertEquals("integer", blueprint.get("dismantle_time_seconds"));
    assertEquals("double precision", blueprint.get("dismantle_efficiency"));

    Map<String, String> ingredient = dataTypesOf("blueprint_ingredient");
    assertEquals("uuid", ingredient.get("requirement_group_id"));
    assertEquals("integer", ingredient.get("min_quality"));
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
