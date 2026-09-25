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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Migration test for {@code V199__add_mission_objectives.sql}: the {@code mission_objective} table,
 * the {@code mission.objectives_version} counter and FK index exist, {@code mission.objective} is
 * gone, and the mapping validates.
 */
@SpringBootTest
@ActiveProfiles("test")
class V199MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v199CreatesMissionObjectiveTable() {
    Map<String, String> objective = dataTypesOf("mission_objective");
    assertEquals("uuid", objective.get("id"));
    assertEquals("uuid", objective.get("mission_id"));
    assertEquals("character varying", objective.get("title"));
    assertEquals("character varying", objective.get("kind"));
    assertEquals("integer", objective.get("order_index"));
    assertEquals("bigint", objective.get("version"));
    assertEquals("timestamp with time zone", objective.get("created_at"));
    assertEquals("timestamp with time zone", objective.get("updated_at"));
  }

  @Test
  void v199AddsObjectivesVersionSectionCounterToMission() {
    Map<String, String> mission = dataTypesOf("mission");
    assertEquals("bigint", mission.get("objectives_version"));
  }

  @Test
  void v199DropsTheLegacySingleObjectiveColumn() {
    Map<String, String> mission = dataTypesOf("mission");
    assertFalse(
        mission.containsKey("objective"),
        "the single mission.objective column is replaced by the structured goals");
  }

  @Test
  void v199IndexesTheMissionForeignKey() {
    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'mission_objective'", String.class);
    assertTrue(
        indexes.contains("uq_mission_objective_mission_order"),
        "the (mission_id, order_index) ordering must stay indexed (now via the V208 unique"
            + " constraint's backing index)");
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
