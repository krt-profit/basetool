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
 * TestContainers-backed migration test for {@code V107__add_cross_ref_columns_to_manufacturer.sql}.
 * Asserts every column declared by the migration is present on {@code manufacturer} with the
 * expected data type, and that the two {@code UNIQUE} constraints ({@code uex_company_id}, {@code
 * scwiki_uuid}) exist so the R2 / R6 sync services can rely on them.
 */
@SpringBootTest
class V107MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v107AddsCrossRefColumnsToManufacturer() {
    Map<String, String> dataTypes = dataTypesOf("manufacturer");

    assertEquals("integer", dataTypes.get("uex_company_id"));
    assertEquals("uuid", dataTypes.get("scwiki_uuid"));
    assertEquals("character varying", dataTypes.get("scwiki_code"));
    assertEquals("character varying", dataTypes.get("industry"));
    assertEquals("boolean", dataTypes.get("is_item_manufacturer"));
    assertEquals("boolean", dataTypes.get("is_vehicle_manufacturer"));
    assertEquals("timestamp with time zone", dataTypes.get("uex_synced_at"));
    assertEquals("timestamp with time zone", dataTypes.get("scwiki_synced_at"));
    assertEquals("timestamp with time zone", dataTypes.get("uex_deleted_at"));
    assertEquals("timestamp with time zone", dataTypes.get("scwiki_deleted_at"));
  }

  @Test
  void v107AddsUniqueConstraints() {
    assertConstraintCount("uk_manufacturer_uex_company_id", 1);
    assertConstraintCount("uk_manufacturer_scwiki_uuid", 1);
  }

  private void assertConstraintCount(String constraintName, int expected) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'manufacturer' "
                + "AND constraint_type = 'UNIQUE' "
                + "AND constraint_name = ?",
            Integer.class,
            constraintName);
    assertEquals(
        expected,
        count == null ? 0 : count,
        "UNIQUE constraint " + constraintName + " must exist on manufacturer");
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
