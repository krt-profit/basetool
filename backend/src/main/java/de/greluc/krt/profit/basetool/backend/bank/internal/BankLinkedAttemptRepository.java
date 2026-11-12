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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reads immutable attempt references and records final no-ledger authority. */
interface BankLinkedAttemptRepository extends Repository<BankLinkedAttemptRecord, UUID> {

  /**
   * Finds the immutable attempt and request facts before the request row is locked.
   *
   * @param requestId the Bank request
   * @return the linked request facts, or empty for an ordinary request
   */
  @Query(
      """
      SELECT a.id AS attemptId, a.purposeRef AS purposeRef, r.type AS type,
        account.id AS accountId, r.requestedBy AS requesterId
      FROM BankBookingRequest r JOIN r.linkedAttempt a LEFT JOIN r.account account
      WHERE r.id = :requestId
      """)
  Optional<RequestLink> findRequestLink(@Param("requestId") UUID requestId);

  /**
   * Finds a posted attempt before the reversal acquires Bank row locks.
   *
   * @param transactionId the original ledger transaction
   * @return the linked identity, or empty for an ordinary transaction
   */
  @Query(
      """
      SELECT a.id AS attemptId, a.purposeRef AS purposeRef
      FROM BankLinkedAttemptRecord a WHERE a.transactionId = :transactionId
      """)
  Optional<AttemptLink> findPostingLink(@Param("transactionId") UUID transactionId);

  /**
   * Records the final authority for the funding account, custodian and amount.
   *
   * @param id the Bank attempt's authorisation identity
   * @param accountId the funding account
   * @param holderId the registered custodian, absent for account-only authority
   * @param amount the authorised principal
   * @param actor the current Bank staff actor
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO bank_authorisation (id, account_id, holder_id, amount, authorised_by, authorised_at)
          VALUES (:id, :accountId, :holderId, :amount, :actor, CURRENT_TIMESTAMP)
          """,
      nativeQuery = true)
  void recordAuthorisation(
      @Param("id") UUID id,
      @Param("accountId") UUID accountId,
      @Param("holderId") @Nullable UUID holderId,
      @Param("amount") BigDecimal amount,
      @Param("actor") UUID actor);

  /** An immutable linked attempt key. */
  interface AttemptLink {
    /**
     * Reads the Bank attempt id.
     *
     * @return the attempt id
     */
    @NotNull
    UUID getAttemptId();

    /**
     * Reads the owner's opaque reference.
     *
     * @return the purpose reference
     */
    @NotNull
    UUID getPurposeRef();
  }

  /** A request's current access facts and immutable linked attempt key. */
  interface RequestLink extends AttemptLink {
    /**
     * Reads the Bank effect type.
     *
     * @return the request type
     */
    @NotNull
    BankBookingRequestType getType();

    /**
     * Reads the principal account.
     *
     * @return the account id, or {@code null} for a holder transfer
     */
    @Nullable
    UUID getAccountId();

    /**
     * Reads the Bank applicant.
     *
     * @return the applicant id, or {@code null} after erasure
     */
    @Nullable
    UUID getRequesterId();
  }
}
