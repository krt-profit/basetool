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
 * Resolves the recipients tied to one bank account or one of its booking requests: the {@code
 * ACCOUNT_GRANT}, {@code ACCOUNT_RESPONSIBLE}, {@code REQUEST_RESPONSIBLE} and {@code
 * REQUIRED_APPROVER} selectors.
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

  /**
   * The responsible holders who are told of every request on an account: those of {@link
   * #responsibleHoldersOf(UUID)}, except on the KRT account (REQ-NOTIF-033).
   *
   * @param accountId the bank account
   * @return their user subs; never {@code null}, empty on the KRT account
   */
  @NotNull
  Set<UUID> requestResponsibleHoldersOf(@NotNull UUID accountId);

  /**
   * The members whose approval a booking request still waits for (REQ-NOTIF-033, REQ-BANK-041).
   *
   * @param requestId the booking request
   * @return their user subs, never the requester; empty when the request is missing, no longer
   *     pending, needs no approval or is already approved
   */
  @NotNull
  Set<UUID> requiredApproversOf(@NotNull UUID requestId);
}
