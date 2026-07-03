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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link BankBookingRequest} rows (epic #666, F2). Unlike the
 * append-only ledger repositories this aggregate is mutable (off-ledger, ADR-0021), so
 * {@code @Modifying} writes via the standard {@code save} path are expected. The {@code account /
 * account.orgUnit / holder / resultingTransaction} graph is eagerly fetched on the list/queue reads
 * so the DTO assembly never triggers an N+1 (REQ-DATA-003).
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
   * Loads one request under a pessimistic write lock for the surrounding transaction. The decision
   * paths (confirm/reject/cancel) lock the request row first so two decisions on the same request
   * serialize — the second blocks until the first commits and then sees the terminal state, which
   * prevents a double-booking before the {@code @Version} check would catch it.
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
   * One page of requests in the given lifecycle state across all accounts — the management/admin
   * confirmation queue (sees every account, REQ-BANK-023).
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
   * One page of requests in the given state restricted to the supplied accounts — the bank-employee
   * confirmation queue, scoped to the accounts the employee is granted on (REQ-BANK-023). An empty
   * id collection yields an empty page.
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
   * Every request on the given accounts, most-recent first — the "Fremde Anträge" tab where a
   * responsible holder sees all requests raised against the accounts they are responsible for
   * (REQ-BANK-041). An empty id collection yields an empty list.
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
}
