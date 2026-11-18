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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Verifies that V437 keeps the output names earlier APPLYs replaced, read from their change
 * reports, only where the old name belonged to one product (REQ-P4K-079).
 */
@SpringBootTest
class V437BlueprintFormerNameMigrationTest {

  @Autowired private DataSource dataSource;

  @Test
  void keepsAReplacedNameOfOneProduct_andLeavesOutANameSeveralProductsCarried() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var sql = connection.createStatement()) {
        sql.execute("DROP TABLE blueprint_former_name");
      }
      UUID run = run(connection);
      UUID helmet = blueprint(connection, "Neutrino Racing Helmet BlackFire");
      UUID suitA = blueprint(connection, "Suit A");
      UUID suitB = blueprint(connection, "Suit B");
      change(connection, run, helmet, "output_name", "BlackFire Racing Helmet");
      change(connection, run, suitA, "output_name", "Shared Suit");
      change(connection, run, suitB, "output_name", "Shared Suit");
      change(connection, run, suitA, "category_id", "Armour");

      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V437__blueprint_former_name.sql"));

      List<String> kept = new ArrayList<>();
      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery(
                  "SELECT b.output_name, f.name FROM blueprint_former_name f"
                      + " JOIN blueprint b ON b.id = f.blueprint_id")) {
        while (rows.next()) {
          kept.add(rows.getString(1) + " <- " + rows.getString(2));
        }
      }
      assertThat(kept)
          .containsExactly("Neutrino Racing Helmet BlackFire <- BlackFire Racing Helmet");
      connection.rollback();
    }
  }

  private static @NotNull UUID run(@NotNull Connection connection) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO p4k_import_run (id, channel, build_branch, build_version, change_number,"
                + " build_id, created_by, applied_at)"
                + " VALUES (?, 'LIVE', 'sc-alpha-4.10', '4.10.197', 1, 'b', ?, now())")) {
      insert.setObject(1, id);
      insert.setObject(2, UUID.randomUUID());
      insert.executeUpdate();
    }
    return id;
  }

  private static @NotNull UUID blueprint(@NotNull Connection connection, @NotNull String outputName)
      throws SQLException {
    UUID guid = UUID.randomUUID();
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO blueprint (id, scwiki_uuid, output_name, p4k_uuid, produced_item_guid)"
                + " VALUES (?, ?, ?, ?, ?)")) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, UUID.randomUUID());
      insert.setString(3, outputName);
      insert.setObject(4, guid);
      insert.setObject(5, UUID.randomUUID());
      insert.executeUpdate();
    }
    return guid;
  }

  private static void change(
      @NotNull Connection connection,
      @NotNull UUID run,
      @NotNull UUID guid,
      @NotNull String field,
      @Nullable String oldValue)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO p4k_import_change (id, run_id, kind, guid, change_type, field_name,"
                + " old_value, new_value) VALUES (?, ?, 'BLUEPRINT', ?, 'CHANGED', ?, ?, 'new')")) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, run);
      insert.setObject(3, guid);
      insert.setString(4, field);
      insert.setString(5, oldValue);
      insert.executeUpdate();
    }
  }
}
