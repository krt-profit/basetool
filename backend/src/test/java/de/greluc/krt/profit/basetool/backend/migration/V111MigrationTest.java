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

/**
 * TestContainers-backed migration test for {@code
 * V111__extend_ship_type_with_uex_and_wiki_fields.sql}. Asserts every column the rewritten {@code
 * UexVehicleService} writes is present on {@code ship_type} and that the cross-source join keys
 * ({@code external_uuid}, {@code uex_vehicle_id}) carry their UNIQUE constraints.
 */
@SpringBootTest
class V111MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v111AddsCrossSourceKeysAndProvenance() {
    Map<String, String> types = dataTypesOf("ship_type");
    assertEquals("uuid", types.get("external_uuid"));
    assertEquals("integer", types.get("uex_vehicle_id"));
    assertEquals("character varying", types.get("uex_slug"));
    assertEquals("character varying", types.get("scwiki_slug"));
    assertEquals("timestamp with time zone", types.get("uex_synced_at"));
    assertEquals("timestamp with time zone", types.get("scwiki_synced_at"));
    assertEquals("character varying", types.get("source_systems"));
  }

  @Test
  void v111AddsVehicleSpecColumns() {
    Map<String, String> types = dataTypesOf("ship_type");
    assertEquals("character varying", types.get("name_full"));
    assertEquals("integer", types.get("crew_min"));
    assertEquals("integer", types.get("crew_max"));
    assertEquals("double precision", types.get("mass"));
    assertEquals("double precision", types.get("mass_total"));
    assertEquals("double precision", types.get("width"));
    assertEquals("double precision", types.get("height"));
    assertEquals("double precision", types.get("length_m"));
    assertEquals("character varying", types.get("pad_type"));
    assertEquals("double precision", types.get("fuel_quantum"));
    assertEquals("double precision", types.get("fuel_hydrogen"));
    assertEquals("double precision", types.get("vehicle_inventory_scu"));
    assertEquals("character varying", types.get("url_store"));
    assertEquals("text", types.get("description_en"));
    assertEquals("text", types.get("description_de"));
  }

  @Test
  void v111AddsThirtySixIsStarBooleanCapabilityFlags() {
    Map<String, String> types = dataTypesOf("ship_type");
    String[] expectedFlags = {
      "is_addon",
      "is_boarding",
      "is_bomber",
      "is_cargo",
      "is_carrier",
      "is_civilian",
      "is_concept",
      "is_construction",
      "is_datarunner",
      "is_docking",
      "is_emp",
      "is_exploration",
      "is_ground_vehicle",
      "is_hangar",
      "is_industrial",
      "is_interdiction",
      "is_loading_dock",
      "is_medical",
      "is_military",
      "is_mining",
      "is_passenger",
      "is_qed",
      "is_quantum_capable",
      "is_racing",
      "is_refinery",
      "is_refuel",
      "is_repair",
      "is_research",
      "is_salvage",
      "is_scanning",
      "is_science",
      "is_showdown_winner",
      "is_spaceship",
      "is_starter",
      "is_stealth",
      "is_tractor_beam"
    };
    for (String flag : expectedFlags) {
      assertEquals("boolean", types.get(flag), "flag column " + flag + " must be boolean");
    }
  }

  @Test
  void v111EnforcesUniqueExternalUuidAndUexVehicleId() {
    Integer extUuid =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'ship_type' "
                + "AND constraint_type = 'UNIQUE' "
                + "AND constraint_name = 'uk_ship_type_external_uuid'",
            Integer.class);
    assertEquals(1, extUuid == null ? 0 : extUuid, "uk_ship_type_external_uuid must exist");

    Integer uexVeh =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'ship_type' "
                + "AND constraint_type = 'UNIQUE' "
                + "AND constraint_name = 'uk_ship_type_uex_vehicle_id'",
            Integer.class);
    assertEquals(1, uexVeh == null ? 0 : uexVeh, "uk_ship_type_uex_vehicle_id must exist");
  }

  @Test
  void v111EnforcesSourceSystemsCheck() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'ship_type' "
                + "AND constraint_type = 'CHECK' "
                + "AND constraint_name = 'chk_ship_type_source_systems'",
            Integer.class);
    assertEquals(1, count == null ? 0 : count, "source_systems CHECK must exist");
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
