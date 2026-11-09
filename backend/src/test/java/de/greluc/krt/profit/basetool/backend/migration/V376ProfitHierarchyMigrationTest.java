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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Replays V376 against synthetic legacy data without guessing missing organisational history. */
@SpringBootTest
class V376ProfitHierarchyMigrationTest {

  @Autowired private DataSource dataSource;

  @Test
  void legacyFlagsAndUnattributedStockRemainFactsAndOrderOriginsRemainUnknown() throws Exception {
    try (Connection connection = fixture()) {
      try (Statement sql = connection.createStatement()) {
        sql.execute(
            "INSERT INTO org_unit VALUES ('00000000-0000-0000-0000-000000000001', 'BEREICH',"
                + " 'PROFIT', true, false, null)");
        sql.execute(
            "INSERT INTO org_unit VALUES ('00000000-0000-0000-0000-000000000002', 'SQUADRON', null,"
                + " true, false, '00000000-0000-0000-0000-000000000001')");
        sql.execute(
            "INSERT INTO org_unit VALUES ('00000000-0000-0000-0000-000000000003', 'SQUADRON', null,"
                + " true, true, null)");
        sql.execute(
            "INSERT INTO job_order VALUES ('00000000-0000-0000-0000-000000000010',"
                + " '00000000-0000-0000-0000-000000000002')");
        sql.execute(
            "INSERT INTO inventory_item VALUES ('00000000-0000-0000-0000-000000000020', 12.5)");
        sql.execute(
            "INSERT INTO material_claim VALUES ('00000000-0000-0000-0000-000000000010',"
                + " '00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000040',"
                + " '00000000-0000-0000-0000-000000000002', 3.0),"
                + " ('00000000-0000-0000-0000-000000000010',"
                + " '00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000040',"
                + " '00000000-0000-0000-0000-000000000003', 5.0)");
      }

      migrate(connection);

      try (Statement sql = connection.createStatement()) {
        try (var row =
            sql.executeQuery(
                "SELECT count(*) FROM org_unit WHERE (id = '00000000-0000-0000-0000-000000000002'"
                    + " AND is_profit_eligible = false AND parent_org_unit_id ="
                    + " '00000000-0000-0000-0000-000000000001') OR (id ="
                    + " '00000000-0000-0000-0000-000000000003' AND is_profit_eligible = true AND"
                    + " parent_org_unit_id IS NULL)")) {
          row.next();
          assertThat(row.getInt(1)).isEqualTo(2);
        }
        try (var row =
            sql.executeQuery(
                "SELECT original_profit_org_unit_id, responsible_org_unit_id FROM job_order")) {
          row.next();
          assertThat(row.getObject(1)).isNull();
          assertThat(row.getObject(2).toString()).isEqualTo("00000000-0000-0000-0000-000000000002");
        }
        try (var row =
            sql.executeQuery(
                "SELECT claiming_org_unit_id, amount, ended_at FROM material_claim ORDER BY"
                    + " claiming_org_unit_id")) {
          assertThat(row.next()).isTrue();
          assertThat(row.getObject(1).toString()).isEqualTo("00000000-0000-0000-0000-000000000002");
          assertThat(row.getDouble(2)).isEqualTo(3.0);
          assertThat(row.getObject(3)).isNull();
          assertThat(row.next()).isTrue();
          assertThat(row.getObject(1).toString()).isEqualTo("00000000-0000-0000-0000-000000000003");
          assertThat(row.getDouble(2)).isEqualTo(5.0);
          assertThat(row.getObject(3)).isNull();
          assertThat(row.next()).isFalse();
        }
        try (var row = sql.executeQuery("SELECT amount FROM inventory_item")) {
          row.next();
          assertThat(row.getDouble(1)).isEqualTo(12.5);
        }
      }
      connection.rollback();
    }
  }

  @Test
  void contradictoryProfitDesignationsRequireResolutionInsteadOfPickingAFirstRoot()
      throws Exception {
    try (Connection connection = fixture()) {
      try (Statement sql = connection.createStatement()) {
        sql.execute(
            "INSERT INTO org_unit VALUES (gen_random_uuid(), 'BEREICH', 'PROFIT', true, false,"
                + " null), (gen_random_uuid(), 'BEREICH', 'PROFIT', true, false, null)");
      }
      assertThatThrownBy(() -> migrate(connection))
          .hasRootCauseMessage(
              "ERROR: could not create unique index \"uq_org_unit_profit_designation\"\n"
                  + "  Detail: Key (department)=(PROFIT) is duplicated.");
      connection.rollback();
    }
  }

  @Test
  void anAbsentProfitRootDoesNotInventOneOrReparentLegacyUnits() throws Exception {
    try (Connection connection = fixture()) {
      try (Statement sql = connection.createStatement()) {
        sql.execute(
            "INSERT INTO org_unit VALUES (gen_random_uuid(), 'SQUADRON', null, true, true, null)");
      }
      migrate(connection);
      try (Statement sql = connection.createStatement();
          var row =
              sql.executeQuery(
                  "SELECT count(*) FROM org_unit WHERE department = 'PROFIT' OR parent_org_unit_id"
                      + " IS NOT NULL")) {
        row.next();
        assertThat(row.getInt(1)).isZero();
      }
      connection.rollback();
    }
  }

  private Connection fixture() throws Exception {
    Connection connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    String schema = "s02_" + UUID.randomUUID().toString().replace("-", "");
    try (Statement sql = connection.createStatement()) {
      sql.execute("CREATE SCHEMA " + schema);
      sql.execute("SET LOCAL search_path TO " + schema);
      sql.execute(
          "CREATE TABLE org_unit (id UUID PRIMARY KEY, kind TEXT, department TEXT, active BOOLEAN,"
              + " is_profit_eligible BOOLEAN, parent_org_unit_id UUID)");
      sql.execute("CREATE TABLE job_order (id UUID PRIMARY KEY, responsible_org_unit_id UUID)");
      sql.execute("CREATE TABLE inventory_item (id UUID PRIMARY KEY, amount NUMERIC)");
      sql.execute("CREATE TABLE notification_rule (id UUID PRIMARY KEY)");
      sql.execute(
          "CREATE TABLE notification_rule_selector (id UUID PRIMARY KEY, rule_id UUID, kind TEXT)");
      sql.execute("INSERT INTO notification_rule VALUES ('62200000-0000-0000-0000-000000000023')");
      sql.execute(
          "CREATE TABLE material_claim (job_order_id UUID, material_id UUID, quality_tier_id UUID,"
              + " claiming_org_unit_id UUID, amount NUMERIC)");
      sql.execute(
          "CREATE UNIQUE INDEX uq_material_claim_tier_bucket_org_unit ON"
              + " material_claim(job_order_id, material_id, quality_tier_id,"
              + " claiming_org_unit_id)");
    }
    return connection;
  }

  private static void migrate(Connection connection) {
    ScriptUtils.executeSqlScript(
        connection,
        new ClassPathResource("db/migration/V376__profit_hierarchy_job_order_authority.sql"));
  }
}
