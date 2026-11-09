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
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Checks that V378 retains only concrete organisational facts verifiable in the migrated data. */
@SpringBootTest
class V378JobOrderUnitReadingMigrationTest {

  @Autowired private DataSource dataSource;

  @Test
  void currentPartiesAndActiveOrEndedClaimsRetainReadWithoutInventedEarlierUnits()
      throws Exception {
    UUID order = UUID.randomUUID();
    UUID responsible = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    UUID activeClaim = UUID.randomUUID();
    UUID endedClaim = UUID.randomUUID();
    UUID unrelated = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement sql = connection.createStatement()) {
        String schema = "s03_" + UUID.randomUUID().toString().replace("-", "");
        sql.execute("CREATE SCHEMA " + schema);
        sql.execute("SET LOCAL search_path TO " + schema);
        sql.execute("CREATE TABLE org_unit (id UUID PRIMARY KEY)");
        sql.execute(
            "CREATE TABLE job_order (id UUID PRIMARY KEY, responsible_org_unit_id UUID,"
                + " requesting_org_unit_id UUID)");
        sql.execute(
            "CREATE TABLE material_claim (job_order_id UUID, claiming_org_unit_id UUID,"
                + " ended_at TIMESTAMP WITH TIME ZONE)");
        for (UUID unit : Set.of(responsible, requester, activeClaim, endedClaim, unrelated)) {
          sql.execute("INSERT INTO org_unit VALUES ('" + unit + "')");
        }
        sql.execute(
            "INSERT INTO job_order VALUES ('"
                + order
                + "', '"
                + responsible
                + "', '"
                + requester
                + "')");
        sql.execute(
            "INSERT INTO material_claim VALUES ('"
                + order
                + "', '"
                + activeClaim
                + "', NULL), ('"
                + order
                + "', '"
                + endedClaim
                + "', CURRENT_TIMESTAMP), ('"
                + order
                + "', '"
                + responsible
                + "', NULL)");
      }

      ScriptUtils.executeSqlScript(
          connection,
          new ClassPathResource("db/migration/V378__retain_observed_job_order_unit_reading.sql"));

      try (Statement sql = connection.createStatement();
          var rows = sql.executeQuery("SELECT org_unit_id FROM job_order_unit_read_history")) {
        Set<UUID> observed = new LinkedHashSet<>();
        while (rows.next()) {
          observed.add(rows.getObject(1, UUID.class));
        }
        assertThat(observed)
            .containsExactlyInAnyOrder(responsible, requester, activeClaim, endedClaim)
            .doesNotContain(unrelated);
      }
      try (Statement sql = connection.createStatement();
          var rows = sql.executeQuery("SELECT count(*) FROM job_order_unit_read_history")) {
        rows.next();
        assertThat(rows.getInt(1)).isEqualTo(4);
      }
      connection.rollback();
    }
  }
}
