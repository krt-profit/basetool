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
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Verifies scoped edit versions and retained Staffel participation on existing orders. */
@SpringBootTest
class JobOrderEditVersionsMigrationTest {

  @Autowired private DataSource dataSource;

  @Test
  void initializesSectionVersionsAndEndsOnlyActiveSelfSupport() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var sql = connection.createStatement()) {
        sql.execute(
            """
            CREATE TEMP TABLE job_order (
                id UUID PRIMARY KEY, responsible_org_unit_id UUID NOT NULL, version BIGINT NOT NULL);
            CREATE TEMP TABLE job_order_participation (
                id UUID PRIMARY KEY, job_order_id UUID NOT NULL REFERENCES job_order(id),
                claiming_org_unit_id UUID NOT NULL, active BOOLEAN NOT NULL, version BIGINT NOT NULL);
            CREATE TEMP TABLE material_claim (
                participation_id UUID NOT NULL REFERENCES job_order_participation(id));
            INSERT INTO job_order VALUES (gen_random_uuid(), gen_random_uuid(), 41);
            INSERT INTO job_order_participation
                SELECT gen_random_uuid(), id, responsible_org_unit_id, TRUE, 5 FROM job_order
                UNION ALL SELECT gen_random_uuid(), id, gen_random_uuid(), TRUE, 7 FROM job_order
                UNION ALL SELECT gen_random_uuid(), id, gen_random_uuid(), FALSE, 11 FROM job_order;
            INSERT INTO material_claim SELECT id FROM job_order_participation WHERE version = 5;
            """);
      }

      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V383__job_order_edit_versions.sql"));

      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery(
                  "SELECT version, lifecycle_version, responsibility_version FROM job_order")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong("version")).isEqualTo(41);
        assertThat(rows.getLong("lifecycle_version")).isZero();
        assertThat(rows.getLong("responsibility_version")).isZero();
      }
      try (var sql = connection.createStatement();
          var rows =
              sql.executeQuery(
                  """
                  SELECT participation.version, participation.active, claim.participation_id IS NOT NULL AS contributed
                  FROM job_order_participation participation
                  LEFT JOIN material_claim claim ON claim.participation_id = participation.id
                  ORDER BY participation.version
                  """)) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong("version")).isEqualTo(6);
        assertThat(rows.getBoolean("active")).isFalse();
        assertThat(rows.getBoolean("contributed")).isTrue();
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong("version")).isEqualTo(7);
        assertThat(rows.getBoolean("active")).isTrue();
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong("version")).isEqualTo(11);
        assertThat(rows.getBoolean("active")).isFalse();
        assertThat(rows.next()).isFalse();
      }
      connection.rollback();
    }
  }
}
