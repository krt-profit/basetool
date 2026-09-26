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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for the mutable, off-ledger {@link BankBookingRequest} aggregate
 * (ADR-0021). List and queue reads fetch the account, org unit, holder and resulting transaction
 * eagerly (REQ-DATA-003).
 */
@Repository
public interface BankBookingRequestRepository extends JpaRepository<BankBookingRequest, UUID> {

  /**
   * Counts booking requests in the given status, backing the {@code
   * basetool_bank_booking_request_pending_*} queue-depth gauge (REQ-OBS-011).
   *
   * @param status the bounded request status to count (typically {@code PENDING})
   * @return the number of requests in that status
   */
  long countByStatus(BankBookingRequestStatus status);

  /**
   * Finds the creation timestamp of the oldest booking request in the given status, for the "oldest
   * pending booking request age" gauge (REQ-OBS-011).
   *
   * @param status the bounded request status to scan (typically {@code PENDING})
   * @return the earliest {@code createdAt} in that status, or {@code null} when none exists
   */
  @Query("SELECT MIN(r.createdAt) FROM BankBookingRequest r WHERE r.status = :status")
  Instant findOldestCreatedAtByStatus(@Param("status") BankBookingRequestStatus status);

  /**
   * Loads one request under a pessimistic write lock, so two decisions on the same request
   * serialize and the second sees the terminal state.
   *
   * @param id the request id
   * @return the locked request, or empty when it does not exist
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM BankBookingRequest r WHERE r.id = :id")
  Optional<BankBookingRequest> findByIdForUpdate(@Param("id") UUID id);

  /**
   * The requester's own requests, most-recent first — the officer/lead "my requests" list
   * (REQ-BANK-022). Per-user isolation: callers only ever pass their own {@code sub}.
   *
   * @param requestedBy the requesting user's id
   * @return the requester's requests, newest first
   */
  @EntityGraph(
      attributePaths = {
        "account",
        "account.orgUnit",
        "targetAccount",
        "holder",
        "holder.user",
        "resultingTransaction"
      })
  List<BankBookingRequest> findByRequestedByOrderByCreatedAtDesc(UUID requestedBy);

  /**
   * Returns one page of requests in the given state across all accounts, the management/admin
   * confirmation queue (REQ-BANK-023).
   *
   * @param status the lifecycle state to list (e.g. {@code PENDING})
   * @param pageable page, size and whitelisted sort
   * @return one page of requests in that state
   */
  @EntityGraph(
      attributePaths = {
        "account",
        "account.orgUnit",
        "targetAccount",
        "holder",
        "holder.user",
        "resultingTransaction"
      })
  Page<BankBookingRequest> findByStatus(BankBookingRequestStatus status, Pageable pageable);

  /**
   * Returns one page of requests in the given state on the supplied accounts, the bank-employee
   * confirmation queue (REQ-BANK-023). An empty id collection yields an empty page.
   *
   * @param status the lifecycle state to list
   * @param accountIds the accounts the employee may see
   * @param pageable page, size and whitelisted sort
   * @return one page of requests in that state on those accounts
   */
  @EntityGraph(
      attributePaths = {
        "account",
        "account.orgUnit",
        "targetAccount",
        "holder",
        "holder.user",
        "resultingTransaction"
      })
  Page<BankBookingRequest> findByStatusAndAccountIdIn(
      BankBookingRequestStatus status, Collection<UUID> accountIds, Pageable pageable);

  /**
   * Returns one page of requests in any of the given states across all accounts (REQ-BANK-023). An
   * empty status collection yields an empty page.
   *
   * @param statuses the lifecycle states to include (any-of)
   * @param pageable page, size and whitelisted sort
   * @return one page of requests in any of those states
   */
  @EntityGraph(
      attributePaths = {
        "account",
        "account.orgUnit",
        "targetAccount",
        "holder",
        "holder.user",
        "resultingTransaction"
      })
  Page<BankBookingRequest> findByStatusIn(
      Collection<BankBookingRequestStatus> statuses, Pageable pageable);

  /**
   * Returns one page of requests in any of the given states on the supplied accounts
   * (REQ-BANK-023). An empty status or account collection yields an empty page.
   *
   * @param statuses the lifecycle states to include (any-of)
   * @param accountIds the accounts the employee may see
   * @param pageable page, size and whitelisted sort
   * @return one page of requests in any of those states on those accounts
   */
  @EntityGraph(
      attributePaths = {
        "account",
        "account.orgUnit",
        "targetAccount",
        "holder",
        "holder.user",
        "resultingTransaction"
      })
  Page<BankBookingRequest> findByStatusInAndAccountIdIn(
      Collection<BankBookingRequestStatus> statuses,
      Collection<UUID> accountIds,
      Pageable pageable);

  /**
   * Lists every request on the given accounts, newest first, for the responsible holder's "Fremde
   * Anträge" tab (REQ-BANK-041). An empty id collection yields an empty list.
   *
   * @param accountIds the accounts the caller is responsible for
   * @return the requests on those accounts, newest first
   */
  @EntityGraph(
      attributePaths = {
        "account",
        "account.orgUnit",
        "targetAccount",
        "holder",
        "holder.user",
        "resultingTransaction"
      })
  List<BankBookingRequest> findByAccountIdInOrderByCreatedAtDesc(Collection<UUID> accountIds);

  /**
   * Existence probe backing the close-account guard (REQ-BANK-025): an account with an open request
   * cannot be closed.
   *
   * @param accountId the account
   * @param status the lifecycle state to probe (the close guard passes {@code PENDING})
   * @return {@code true} when at least one matching request exists
   */
  boolean existsByAccountIdAndStatus(UUID accountId, BankBookingRequestStatus status);

  /**
   * Replaces this member's handle snapshots in all four handle columns of the booking requests
   * (requester, deciding employee, counterparty, approving holder) with the erasure sentinel, for a
   * granted Art. 17 request (REQ-SEC-062).
   *
   * <p>Bulk update; does not bump {@code version}.
   *
   * @param userId the member whose handle snapshots are erased
   * @param sentinel {@code HandleAnonymisation#SENTINEL}
   * @return the number of rows rewritten
   */
  @Modifying
  @Query(
      """
      UPDATE BankBookingRequest r SET
        r.requesterHandle = CASE WHEN r.requestedBy = :userId THEN :sentinel
                                 ELSE r.requesterHandle END,
        r.deciderHandle = CASE WHEN r.decidedBy = :userId THEN :sentinel
                               ELSE r.deciderHandle END,
        r.counterpartyHandle = CASE WHEN r.counterpartyUserId = :userId THEN :sentinel
                                    ELSE r.counterpartyHandle END,
        r.ownerApprovalGrantedByHandle = CASE WHEN r.ownerApprovalGrantedBy = :userId THEN :sentinel
                                              ELSE r.ownerApprovalGrantedByHandle END
      WHERE r.requestedBy = :userId OR r.decidedBy = :userId
         OR r.counterpartyUserId = :userId OR r.ownerApprovalGrantedBy = :userId
      """)
  int anonymiseHandles(@Param("userId") UUID userId, @Param("sentinel") String sentinel);
}
