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

package de.greluc.krt.profit.basetool.backend.notification.api;

import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Resolves the recipients tied to one bank account: the {@code ACCOUNT_GRANT} and {@code
 * ACCOUNT_RESPONSIBLE} selectors.
 *
 * <p>Owned by the notification module and implemented by the bank module.
 */
public interface AccountRecipientDirectory {

  /**
   * The bank employees holding a {@code bank_account_grant} on an account (REQ-BANK-026).
   *
   * @param accountId the bank account
   * @return the grantees' user subs; never {@code null}, possibly empty
   */
  @NotNull
  Set<UUID> grantHoldersOf(@NotNull UUID accountId);

  /**
   * The responsible holders of an account (REQ-BANK-034).
   *
   * @param accountId the bank account
   * @return their user subs; never {@code null}, empty for a Sonderkonto or an unlinked account
   */
  @NotNull
  Set<UUID> responsibleHoldersOf(@NotNull UUID accountId);
}
