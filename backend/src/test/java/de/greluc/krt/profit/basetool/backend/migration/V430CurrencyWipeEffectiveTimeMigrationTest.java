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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies that after V430 only a wipe reset carries an effective time on the bank ledger and only
 * a currency-wipe shortage on the order money ledger (REQ-PATCH-041, REQ-PATCH-044).
 */
@SpringBootTest
class V430CurrencyWipeEffectiveTimeMigrationTest {

  private static final Timestamp LIVE_AT = Timestamp.from(Instant.parse("2000-01-01T00:00:00Z"));

  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void aWipeResetKeepsItsEffectiveTime() {
    inRolledBackTransaction(() -> {
      UUID reset = bankTransaction("WIPE_RESET");
      jdbc.update("UPDATE bank_transaction SET effective_at = ? WHERE id = ?", LIVE_AT, reset);

      assertThat(
              jdbc.queryForObject(
                  "SELECT effective_at FROM bank_transaction WHERE id = ?", Timestamp.class, reset))
          .isEqualTo(LIVE_AT);
    });
  }

  @Test
  void anyOtherBankTransactionRefusesAnEffectiveTime() {
    inRolledBackTransaction(() -> {
      UUID deposit = bankTransaction("DEPOSIT");

      assertThatThrownBy(() ->
              jdbc.update(
                  "UPDATE bank_transaction SET effective_at = ? WHERE id = ?", LIVE_AT, deposit))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("chk_bank_transaction_effective_at");
    });
  }

  @Test
  void anOrderMoneyOperationCarriesAnEffectiveTimeOnlyWithAPatchChange() {
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                    + " WHERE conname = 'chk_job_order_money_operation_effective_at'",
                String.class))
        .isEqualTo("CHECK (((effective_at IS NULL) OR (patch_change_id IS NOT NULL)))");
  }

  /**
   * Runs work in a transaction that is rolled back afterwards, so nothing it writes stays.
   *
   * @param work the work
   */
  private void inRolledBackTransaction(@NotNull Runnable work) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          work.run();
          status.setRollbackOnly();
        });
  }

  /**
   * Inserts a bank transaction header without legs.
   *
   * @param type the transaction type
   * @return its id
   */
  private @NotNull UUID bankTransaction(@NotNull String type) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_transaction (id, type, transfer_fee, created_at) VALUES (?, ?, 0, now())",
        id,
        type);
    return id;
  }
}
