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
 * TestContainers-backed migration test for {@code V113__create_external_sync_report.sql}. Asserts
 * the audit table exists with every column from SC_WIKI_SYNC_PLAN.md §8.8, the {@code
 * source_system} CHECK, and the two indexes the admin pages + retention sweep rely on.
 */
@SpringBootTest
class V113MigrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void v113CreatesExternalSyncReportTable() {
    Map<String, String> types = dataTypesOf("external_sync_report");
    assertEquals("uuid", types.get("id"));
    assertEquals("uuid", types.get("run_id"));
    assertEquals("timestamp with time zone", types.get("ran_at"));
    assertEquals("character varying", types.get("source_system"));
    assertEquals("character varying", types.get("event_type"));
    assertEquals("character varying", types.get("aggregate"));
    assertEquals("uuid", types.get("external_uuid"));
    assertEquals("integer", types.get("external_id"));
    assertEquals("character varying", types.get("external_name"));
    assertEquals("text", types.get("detail"));
  }

  @Test
  void v113AddsSourceSystemCheck() {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM information_schema.table_constraints "
                + "WHERE table_name = 'external_sync_report' "
                + "AND constraint_type = 'CHECK' "
                + "AND constraint_name = 'chk_external_sync_report_source_system'",
            Integer.class);
    assertEquals(1, count == null ? 0 : count, "source_system CHECK must exist");
  }

  @Test
  void v113AddsRunAndSourceIndexes() {
    assertIndexExists("idx_external_sync_report_run");
    assertIndexExists("idx_external_sync_report_source");
  }

  private void assertIndexExists(String indexName) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM pg_indexes "
                + "WHERE tablename = 'external_sync_report' AND indexname = ?",
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
