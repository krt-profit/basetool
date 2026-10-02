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

/**
 * TestContainers-backed migration test for {@code V115__create_game_item_price.sql}. Asserts the
 * {@code game_item_price} table exists with the §6.7 columns and types, the {@code (game_item_id,
 * terminal_id)} UNIQUE constraint, and the terminal index.
 */
@SpringBootTest
class V115MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v115CreatesGameItemPriceTableWithExpectedColumns() {
    Map<String, String> types = dataTypesOf("game_item_price");
    assertEquals("uuid", types.get("id"));
    assertEquals("bigint", types.get("version"));
    assertEquals("timestamp with time zone", types.get("created_at"));
    assertEquals("timestamp with time zone", types.get("updated_at"));
    assertEquals("uuid", types.get("game_item_id"));
    assertEquals("uuid", types.get("terminal_id"));
    assertEquals("double precision", types.get("price_buy"));
    assertEquals("double precision", types.get("price_sell"));
    assertEquals("double precision", types.get("price_rent"));
    assertEquals("integer", types.get("status_buy"));
    assertEquals("integer", types.get("status_sell"));
    assertEquals("bigint", types.get("date_modified"));
    assertEquals("character varying", types.get("game_version"));
    assertEquals("timestamp with time zone", types.get("uex_synced_at"));
  }

  @Test
  void v115AddsItemTerminalUniqueConstraint() {
    Integer uk =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'game_item_price' AND constraint_type = 'UNIQUE' "
                + "AND constraint_name = 'uk_game_item_price_item_terminal'",
            Integer.class);
    assertEquals(
        1, uk == null ? 0 : uk, "(game_item_id, terminal_id) UNIQUE constraint must exist");
  }

  @Test
  void v115AddsTerminalIndex() {
    Integer idx =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE tablename = 'game_item_price' AND indexname ="
                + " 'idx_game_item_price_terminal'",
            Integer.class);
    assertEquals(1, idx == null ? 0 : idx, "terminal index must exist");
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
