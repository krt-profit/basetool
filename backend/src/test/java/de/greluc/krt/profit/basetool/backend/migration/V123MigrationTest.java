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
 * Migration test for {@code V123__add_item_job_orders.sql}: the {@code job_order.type}
 * discriminator and the four item-order tables exist, and the item-order entities validate against
 * the schema.
 */
@SpringBootTest
@ActiveProfiles("test")
class V123MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v123AddsJobOrderTypeDiscriminatorDefaultingToMaterial() {
    Map<String, String> jobOrder = dataTypesOf("job_order");
    assertEquals("character varying", jobOrder.get("type"));

    Long nonMaterial =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM job_order WHERE type IS NULL OR type <> 'MATERIAL'", Long.class);
    assertEquals(0L, nonMaterial, "existing rows must be backfilled to MATERIAL");
  }

  @Test
  void v123CreatesJobOrderItemTable() {
    Map<String, String> item = dataTypesOf("job_order_item");
    assertEquals("uuid", item.get("id"));
    assertEquals("uuid", item.get("job_order_id"));
    assertEquals("uuid", item.get("game_item_id"));
    assertEquals("uuid", item.get("blueprint_id"));
    assertEquals("integer", item.get("amount"));
    assertEquals("integer", item.get("delivered_amount"));
    assertEquals("uuid", item.get("parent_item_id"));
    assertEquals("bigint", item.get("version"));
  }

  @Test
  void v123CreatesJobOrderItemMaterialTable() {
    Map<String, String> material = dataTypesOf("job_order_item_material");
    assertEquals("uuid", material.get("job_order_item_id"));
    assertEquals("uuid", material.get("material_id"));
    assertEquals("double precision", material.get("required_quantity"));
    assertEquals("character varying", material.get("quality_requirement"));
  }

  @Test
  void v123CreatesItemHandoverTables() {
    Map<String, String> handover = dataTypesOf("job_order_item_handover");
    assertEquals("uuid", handover.get("job_order_id"));
    assertEquals("timestamp with time zone", handover.get("handover_time"));
    assertEquals("character varying", handover.get("recipient_handle"));
    assertEquals("uuid", handover.get("executing_user_id"));
    assertEquals("uuid", handover.get("executing_squadron_id"));

    Map<String, String> entry = dataTypesOf("job_order_item_handover_entry");
    assertEquals("uuid", entry.get("job_order_item_handover_id"));
    assertEquals("uuid", entry.get("job_order_item_id"));
    assertEquals("integer", entry.get("amount"));
  }

  @Test
  void v123IndexesEveryNewForeignKey() {
    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename IN "
                + "('job_order_item', 'job_order_item_material', 'job_order_item_handover', "
                + "'job_order_item_handover_entry')",
            String.class);
    assertTrue(indexes.contains("idx_job_order_item_job_order"));
    assertTrue(indexes.contains("idx_job_order_item_blueprint"));
    assertTrue(indexes.contains("idx_job_order_item_material_material"));
    assertTrue(indexes.contains("idx_job_order_item_handover_entry_item"));
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
