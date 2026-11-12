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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Detects Bank records attributable to a real PostgreSQL order scenario. */
final class JobOrderBankFootprint {

  private JobOrderBankFootprint() {}

  static void assertAbsent(
      JdbcTemplate jdbc,
      List<UUID> actors,
      List<UUID> units,
      List<String> accountlessParties,
      @Nullable UUID order) {
    var scope =
        new MapSqlParameterSource()
            .addValue("actors", actors)
            .addValue("units", units)
            .addValue("parties", accountlessParties)
            .addValue("orderId", order);
    var bankRecords =
        new NamedParameterJdbcTemplate(jdbc)
            .queryForList(
                """
                WITH accounts AS (
                  SELECT id FROM bank_account WHERE org_unit_id IN (:units)
                ), holders AS (
                  SELECT id FROM bank_holder WHERE user_id IN (:actors) OR handle IN (:parties)
                ), transactions AS (
                  SELECT id FROM bank_transaction
                  WHERE initiated_by IN (:actors) OR counterparty_user_id IN (:actors)
                     OR counterparty_org_unit_id IN (:units) OR counterparty_handle IN (:parties)
                )
                SELECT 'account' FROM accounts
                UNION ALL SELECT 'holder' FROM holders
                UNION ALL SELECT 'transaction' FROM transactions
                UNION ALL SELECT 'account grant' FROM bank_account_grant
                  WHERE user_id IN (:actors) OR account_id IN (SELECT id FROM accounts)
                UNION ALL SELECT 'view grant' FROM bank_account_view_grant
                  WHERE grantee_user_id IN (:actors) OR account_id IN (SELECT id FROM accounts)
                UNION ALL SELECT 'booking request' FROM bank_booking_request
                  WHERE requested_by IN (:actors) OR counterparty_user_id IN (:actors)
                     OR counterparty_org_unit_id IN (:units) OR counterparty_handle IN (:parties)
                     OR purpose_ref = :orderId OR holder_id IN (SELECT id FROM holders)
                     OR account_id IN (SELECT id FROM accounts)
                     OR target_account_id IN (SELECT id FROM accounts)
                UNION ALL SELECT 'posting' FROM bank_posting
                  WHERE account_id IN (SELECT id FROM accounts)
                     OR transaction_id IN (SELECT id FROM transactions)
                UNION ALL SELECT 'holder posting' FROM bank_holder_posting
                  WHERE holder_id IN (SELECT id FROM holders)
                     OR transaction_id IN (SELECT id FROM transactions)
                UNION ALL SELECT 'audit event' FROM bank_audit_event
                  WHERE actor_user_id IN (:actors) OR target_user_id IN (:actors)
                     OR account_id IN (SELECT id FROM accounts)
                     OR transaction_id IN (SELECT id FROM transactions)
                """,
                scope,
                String.class);
    assertThat(bankRecords).as("Bank records for the order fixture").isEmpty();
  }
}
