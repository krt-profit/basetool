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

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Verifies that V418 starts ships with a pledge id or a production status as locked {@code PLEDGE}
 * and every other ship as {@code UNKNOWN} (REQ-HANGAR-017).
 */
@SpringBootTest
class V418ShipOriginMigrationTest {

  @Autowired private DataSource dataSource;

  @Test
  void startsImportedShipsAsLockedPledgeAndTheRestAsUnknown() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var sql = connection.createStatement()) {
        sql.execute(
            """
            CREATE TEMP TABLE ship (
                name VARCHAR(32) PRIMARY KEY, pledge_id VARCHAR(32), production_status VARCHAR(16));
            INSERT INTO ship VALUES ('pledged', '113735507', NULL), ('flight-ready', NULL,
                'FLIGHT_READY'), ('both', '2001', 'IN_CONCEPT'), ('manual', NULL, NULL);
            """);
      }

      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V418__add_ship_origin.sql"));

      Map<String, String> origins = new LinkedHashMap<>();
      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery("SELECT name, origin, origin_locked FROM ship ORDER BY name")) {
        while (rows.next()) {
          origins.put(
              rows.getString("name"),
              rows.getString("origin") + "/" + rows.getBoolean("origin_locked"));
        }
      }
      assertThat(origins)
          .containsExactly(
              Map.entry("both", "PLEDGE/true"),
              Map.entry("flight-ready", "PLEDGE/true"),
              Map.entry("manual", "UNKNOWN/false"),
              Map.entry("pledged", "PLEDGE/true"));
      connection.rollback();
    }
  }
}
