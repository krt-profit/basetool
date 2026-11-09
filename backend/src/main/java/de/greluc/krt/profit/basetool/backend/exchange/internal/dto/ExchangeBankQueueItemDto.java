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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One pending booking request of the staff queue (REQ-BXCH-005): no staff note, no holder fields,
 * no custody balances and no handle but the requester's.
 *
 * @param id the request
 * @param type {@code DEPOSIT}, {@code WITHDRAWAL} or {@code TRANSFER}
 * @param amount the amount in whole aUEC
 * @param createdAt the creation instant
 * @param ageHours the whole hours since {@code createdAt}
 * @param account the (source) account
 * @param targetAccount the destination of a transfer, or {@code null}
 * @param requesterHandle the requester's handle, or {@code null} when they withhold their name
 * @param justification the justification as plain text, or {@code null}
 * @param note the requester's note as plain text, or {@code null}
 * @param requiresOwnerApproval whether the request is over the requester's limit
 * @param requiredApprover {@code RESPONSIBLE_HOLDER}, {@code BANK_MANAGEMENT} or {@code
 *     ORGANISATIONSLEITUNG}, or {@code null}
 * @param ownerApprovalGranted whether the in-app approval is given
 * @param mayConfirm whether the member's grant covers the type and four-eyes does not bar them
 * @param accountBalance the account's balance in whole aUEC for the Bankleitung and for an employee
 *     granted on it, otherwise {@code null}
 * @param client the connected application that raised the request, or {@code null}
 * @param link the absolute {@code https} URL of the request's page in the Basetool
 */
public record ExchangeBankQueueItemDto(
    @NotNull UUID id,
    @NotNull String type,
    long amount,
    @NotNull Instant createdAt,
    long ageHours,
    @NotNull Account account,
    @Nullable Target targetAccount,
    @Nullable String requesterHandle,
    @Nullable String justification,
    @Nullable String note,
    boolean requiresOwnerApproval,
    @Nullable String requiredApprover,
    boolean ownerApprovalGranted,
    boolean mayConfirm,
    @Nullable Long accountBalance,
    @Nullable String client,
    @NotNull String link) {

  /**
   * The (source) account of a request.
   *
   * @param id the account
   * @param no the {@code KB-<n>} account number
   * @param name the display name
   * @param orgUnitShorthand the owning org unit's short label, or {@code null}
   */
  public record Account(
      @NotNull UUID id,
      @NotNull String no,
      @NotNull String name,
      @Nullable String orgUnitShorthand) {}

  /**
   * The destination account of a transfer.
   *
   * @param id the account
   * @param no the {@code KB-<n>} account number
   */
  public record Target(@NotNull UUID id, @NotNull String no) {}
}
