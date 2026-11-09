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

package de.greluc.krt.profit.basetool.backend.bank.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The booking requests a member raised, through any channel, for the member's own view
 * (REQ-BANK-022, REQ-BXCH-003). Only the requester's own rows are read, so no access decision is
 * needed; staff notes, staff identities, counterparties and purposes are never part of a value.
 * Must run inside a transaction.
 */
public interface BankOwnRequestReads {

  /**
   * One page of the member's requests in id order.
   *
   * @param requester the member
   * @param afterId the last request of the previous page, or {@code null} for the first page
   * @param limit the page size, at least 1
   * @return the page
   */
  @NotNull
  OwnRequestPage ownRequests(@NotNull UUID requester, @Nullable UUID afterId, int limit);

  /**
   * The current state of the given requests of the member; an id that is not the member's request
   * is left out.
   *
   * @param requester the member
   * @param ids the request ids
   * @return the member's requests among them, in no particular order
   */
  @NotNull
  @Unmodifiable
  List<OwnRequest> ownRequestsByIds(@NotNull UUID requester, @NotNull Collection<UUID> ids);

  /**
   * One of the member's requests.
   *
   * @param id the request
   * @param version its optimistic-lock version
   * @param status the lifecycle state's name, such as {@code PENDING}
   * @param createdAt when it was raised
   * @param decidedAt when it reached its terminal state, or {@code null} while pending
   * @param type whether it is a deposit, a withdrawal or a transfer
   * @param amount the whole-aUEC amount
   * @param note the requester's note, or {@code null}
   * @param justification the requester's justification, or {@code null}
   * @param account the (source) account
   * @param targetAccount the destination of a transfer, or {@code null}
   * @param requiresOwnerApproval whether the amount exceeded the requester's limit
   * @param requiredApprover who must approve before staff may confirm, or {@code null}
   * @param ownerApprovalGranted whether that approval was granted in the Basetool
   * @param rejectReason the staff's reason for a rejection, or {@code null}
   * @param client the connected application that raised it, or {@code null} for one raised in the
   *     Basetool
   * @param splitEnabled whether a deposit distributes {@code splitPercent} across squadron accounts
   * @param splitPercent the whole percent distributed, or {@code null} without a split
   */
  record OwnRequest(
      @NotNull UUID id,
      long version,
      @NotNull String status,
      @NotNull Instant createdAt,
      @Nullable Instant decidedAt,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount,
      @Nullable String note,
      @Nullable String justification,
      @NotNull Account account,
      @Nullable TargetAccount targetAccount,
      boolean requiresOwnerApproval,
      @Nullable BankRequestApprover requiredApprover,
      boolean ownerApprovalGranted,
      @Nullable String rejectReason,
      @Nullable String client,
      boolean splitEnabled,
      @Nullable BigDecimal splitPercent) {}

  /**
   * The account a request is raised against.
   *
   * @param id the account
   * @param no its {@code KB-<n>} number
   * @param name its display name
   * @param orgUnitShorthand the owning org unit's shorthand, or {@code null} for an account without
   *     one
   */
  record Account(
      @NotNull UUID id,
      @NotNull String no,
      @NotNull String name,
      @Nullable String orgUnitShorthand) {}

  /**
   * The destination of a transfer request.
   *
   * @param id the account
   * @param no its {@code KB-<n>} number
   */
  record TargetAccount(@NotNull UUID id, @NotNull String no) {}

  /**
   * One page of the member's requests.
   *
   * @param items the requests in id order
   * @param hasMore whether a further page follows the last item
   */
  record OwnRequestPage(@NotNull @Unmodifiable List<OwnRequest> items, boolean hasMore) {}
}
