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

import de.greluc.krt.profit.basetool.backend.model.BankTransaction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for the append-only {@link BankTransaction} headers (ADR-0010). No booking
 * fact is updated or deleted; a correction is a {@code REVERSAL} (REQ-BANK-004).
 *
 * <p>The single permitted {@code @Modifying} method is {@link #anonymiseCounterpartyHandle}, which
 * changes only the displayed name (REQ-SEC-062, ADR-0183).
 */
@Repository
public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

  /**
   * Probes whether a transaction has already been reversed — the pre-check behind the stable 409
   * code {@code BANK_ALREADY_REVERSED} (the V153 unique constraint is the backstop).
   *
   * @param reversedTransactionId the original transaction's id
   * @return {@code true} when a {@code REVERSAL} referencing it exists
   */
  boolean existsByReversedTransactionId(UUID reversedTransactionId);

  /**
   * Integrity check (REQ-BANK-020, ADR-0052): ids of {@code TRANSFER} / {@code HOLDER_TRANSFER}
   * transactions whose account legs do not net to {@code -transfer_fee}.
   *
   * @return the violating transaction ids (empty when sound)
   */
  @Query(
      """
      SELECT t.id FROM BankPosting p JOIN p.transaction t WHERE t.type IN (
      de.greluc.krt.profit.basetool.backend.model.BankTransactionType.TRANSFER,
      de.greluc.krt.profit.basetool.backend.model.BankTransactionType.HOLDER_TRANSFER)
      GROUP BY t.id, t.transferFee HAVING SUM(p.amount) + t.transferFee <> 0
      """)
  List<UUID> findTransferTransactionsWithNonZeroSum();

  /**
   * Integrity check (REQ-BANK-020): ids of {@code REVERSAL} transactions whose account legs,
   * combined with the reversed transaction's, do not cancel per account.
   *
   * @return the violating reversal transaction ids (empty when sound)
   */
  @Query(
      value =
          """
          SELECT rt.id FROM bank_transaction rt
          WHERE rt.type = 'REVERSAL' AND EXISTS (
          SELECT 1 FROM bank_posting p
          WHERE p.transaction_id IN (rt.id, rt.reversed_transaction_id)
          GROUP BY p.account_id HAVING SUM(p.amount) <> 0)
          """,
      nativeQuery = true)
  List<UUID> findReversalTransactionsNotMirrored();

  /**
   * Integrity check (REQ-BANK-020): ids of transactions lacking their mandatory audit row
   * (REQ-BANK-012). {@code WIPE_RESET} transactions are excluded.
   *
   * @return the violating transaction ids (empty when every audited type has its row)
   */
  @Query(
      """
      SELECT t.id FROM BankTransaction t WHERE t.type <>
      de.greluc.krt.profit.basetool.backend.model.BankTransactionType.WIPE_RESET
      AND NOT EXISTS (SELECT 1 FROM BankAuditEvent e WHERE e.transactionId = t.id)
      """)
  List<UUID> findTransactionsWithoutAuditEvent();

  /**
   * Replaces this member's counterparty handle snapshot with the erasure sentinel for a granted
   * Art. 17 request (REQ-SEC-062). Amounts, dates and accounts stay untouched.
   *
   * @param userId the member whose handle snapshot is erased
   * @param sentinel {@code HandleAnonymisation#SENTINEL}
   * @return the number of rows rewritten
   */
  @Modifying
  @Query(
      "UPDATE BankTransaction t SET t.counterpartyHandle = :sentinel"
          + " WHERE t.counterpartyUserId = :userId AND t.counterpartyHandle <> :sentinel")
  int anonymiseCounterpartyHandle(@Param("userId") UUID userId, @Param("sentinel") String sentinel);
}
