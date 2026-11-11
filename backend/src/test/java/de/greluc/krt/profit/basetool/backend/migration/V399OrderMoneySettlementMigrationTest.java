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

import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Verifies the additive recipient and paired-correction migration from the installed ledger. */
@Testcontainers
@Timeout(90)
class V399OrderMoneySettlementMigrationTest {
  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse(TestImages.POSTGRES).asCompatibleSubstituteFor("postgres"));

  @Test
  void upgradesAnInstalledSettlementLedgerWithoutChangingItsChecksum() {
    Flyway installed = flyway("397");
    installed.migrate();
    var original = installed.info().current();
    assertThat(original.getVersion().getVersion()).isEqualTo("397");
    Integer checksum = original.getChecksum();
    assertThat(checksum).isNotNull();

    Flyway upgrade = flyway("399");
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
    assertThat(upgrade.validateWithResult().validationSuccessful).isTrue();
    assertThat(upgrade.info().applied())
        .anySatisfy(applied -> {
          assertThat(applied.getVersion().getVersion()).isEqualTo("397");
          assertThat(applied.getChecksum()).isEqualTo(checksum);
        });
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("399");
  }

  private static Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .target(target)
        .load();
  }
}
