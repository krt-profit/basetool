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
 * TestContainers-backed migration test for {@code V109__create_uex_category.sql}. Asserts the
 * {@code uex_category} reference table exists with the expected columns, primary key, type CHECK
 * constraint, and section / type indexes.
 */
@SpringBootTest
class V109MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v109CreatesUexCategoryTable() {
    Map<String, String> types = dataTypesOf("uex_category");

    assertEquals("integer", types.get("id"));
    assertEquals("bigint", types.get("version"));
    assertEquals("character varying", types.get("type"));
    assertEquals("character varying", types.get("section"));
    assertEquals("character varying", types.get("name"));
    assertEquals("boolean", types.get("is_game_related"));
    assertEquals("boolean", types.get("is_mining"));
    assertEquals("timestamp with time zone", types.get("uex_synced_at"));
    assertEquals("timestamp with time zone", types.get("uex_deleted_at"));
  }

  @Test
  void v109AddsTypeCheckConstraint() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'uex_category' "
                + "AND constraint_type = 'CHECK' "
                + "AND constraint_name = 'chk_uex_category_type'",
            Integer.class);
    assertEquals(1, count == null ? 0 : count, "type CHECK constraint must exist");
  }

  @Test
  void v109AddsSectionAndTypeIndexes() {
    Integer sectionIdx =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes "
                + "WHERE tablename = 'uex_category' AND indexname = 'idx_uex_category_section'",
            Integer.class);
    assertEquals(1, sectionIdx == null ? 0 : sectionIdx, "section index must exist");
    Integer typeIdx =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes "
                + "WHERE tablename = 'uex_category' AND indexname = 'idx_uex_category_type'",
            Integer.class);
    assertEquals(1, typeIdx == null ? 0 : typeIdx, "type index must exist");
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
