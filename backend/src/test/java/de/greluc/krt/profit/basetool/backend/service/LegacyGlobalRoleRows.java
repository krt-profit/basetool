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

package de.greluc.krt.profit.basetool.backend.service;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lets a migration test seed {@code GLOBAL_ROLE} bank rows that the current schema rejects.
 *
 * <p>Drops the kind and payload checks of both grantee tables; call it inside the test's
 * rolled-back transaction only.
 */
final class LegacyGlobalRoleRows {

  private LegacyGlobalRoleRows() {}

  /**
   * Drops the grantee-kind and payload checks of the view-grant and approval-limit tables.
   *
   * @param jdbcTemplate the template bound to the test transaction
   */
  static void allowGlobalRole(JdbcTemplate jdbcTemplate) {
    jdbcTemplate.execute(
        "ALTER TABLE bank_account_view_grant DROP CONSTRAINT chk_bank_view_grant_kind,"
            + " DROP CONSTRAINT chk_bank_view_grant_payload");
    jdbcTemplate.execute(
        "ALTER TABLE bank_account_approval_limit DROP CONSTRAINT chk_bank_appr_limit_kind,"
            + " DROP CONSTRAINT chk_bank_appr_limit_payload");
  }
}
