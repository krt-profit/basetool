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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The read-only bank-staff queue of an explicit {@link BankActor}, decided by {@link
 * BankAccessPolicy} on every call (REQ-BXCH-005): the pending requests the web staff queue shows
 * the actor, and the pending requests that wait for the actor's approval. Both sections run oldest
 * first. Must run inside a transaction.
 */
public interface BankStaffQueueReads {

  /**
   * One snapshot page of both sections, each continued after its own key.
   *
   * @param actor the actor
   * @param openAfter the last {@code open} item of the previous page, or {@code null}
   * @param toApproveAfter the last {@code toApprove} item of the previous page, or {@code null}
   * @param limit the page size of each section, at least 1
   * @return the snapshot; empty when the actor holds no bank role and no grant and no request waits
   *     for their approval
   */
  @NotNull
  Optional<Snapshot> snapshot(
      @NotNull BankActor actor,
      @Nullable QueueKey openAfter,
      @Nullable QueueKey toApproveAfter,
      int limit);

  /**
   * A position in the oldest-first order of a section.
   *
   * @param createdAt the request's creation instant
   * @param requestId the request, the tiebreaker within one instant
   */
  record QueueKey(@NotNull Instant createdAt, @NotNull UUID requestId) {}

  /**
   * The account a request debits or credits.
   *
   * @param id the account
   * @param no the {@code KB-<n>} account number
   * @param name the display name
   * @param orgUnitShorthand the owning org unit's short label, or {@code null}
   */
  record AccountRef(
      @NotNull UUID id,
      @NotNull String no,
      @NotNull String name,
      @Nullable String orgUnitShorthand) {}

  /**
   * The destination of a transfer.
   *
   * @param id the account
   * @param no the {@code KB-<n>} account number
   */
  record TargetRef(@NotNull UUID id, @NotNull String no) {}

  /**
   * One pending request, without the staff note, holder fields and every handle but the
   * requester's.
   *
   * @param requestId the request
   * @param type the movement kind
   * @param amount the whole-aUEC amount
   * @param createdAt the creation instant
   * @param account the (source) account
   * @param target the destination of a transfer, or {@code null}
   * @param requesterId the requester, or {@code null}
   * @param requesterHandle the requester's handle snapshot, or {@code null}
   * @param justification the justification, or {@code null}
   * @param note the requester's note, or {@code null}
   * @param requiresOwnerApproval whether the request is over the requester's limit
   * @param requiredApprover the approver class, or {@code null}
   * @param ownerApprovalGranted whether the in-app approval is given
   * @param mayConfirm whether the actor's grant covers the request type and four-eyes does not bar
   *     them
   * @param accountBalance the account's current balance for the Bankleitung and for a granted
   *     employee, otherwise {@code null}
   */
  record Item(
      @NotNull UUID requestId,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount,
      @NotNull Instant createdAt,
      @NotNull AccountRef account,
      @Nullable TargetRef target,
      @Nullable UUID requesterId,
      @Nullable String requesterHandle,
      @Nullable String justification,
      @Nullable String note,
      boolean requiresOwnerApproval,
      @Nullable BankRequestApprover requiredApprover,
      boolean ownerApprovalGranted,
      boolean mayConfirm,
      @Nullable BigDecimal accountBalance) {

    /**
     * The position of this item in its section.
     *
     * @return its key
     */
    @NotNull
    public QueueKey key() {
      return new QueueKey(createdAt, requestId);
    }
  }

  /**
   * One page of a section.
   *
   * @param items the items, oldest first
   * @param hasMore whether a further page follows the last item
   */
  record Section(@NotNull @Unmodifiable List<Item> items, boolean hasMore) {}

  /**
   * One snapshot page of the queue.
   *
   * @param open the web staff queue's requests
   * @param toApprove the requests that wait for the actor's approval
   * @param oldestOpenAt the creation instant of the oldest request in either whole section, or
   *     {@code null} when both are empty
   */
  record Snapshot(
      @NotNull Section open, @NotNull Section toApprove, @Nullable Instant oldestOpenAt) {}
}
