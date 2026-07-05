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
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for the append-only {@link BankTransaction} headers (epic #556, ADR-0010).
 * Strictly insert-and-read: no {@code @Modifying} method may ever appear here — the ledger is never
 * updated or deleted (REQ-BANK-004, pinned by {@code ArchitectureTest}).
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
   * Integrity check (REQ-BANK-020, ADR-0052, #998): ids of {@code TRANSFER} / {@code
   * HOLDER_TRANSFER} transactions whose account legs do not net to {@code -transfer_fee}. A
   * fee-free (same-holder) transfer nets to zero; a fee-bearing holder-changing transfer debits the
   * source the gross (amount + fee) and credits the destination the full entered amount, so its two
   * account legs net to {@code -transfer_fee}. Since #998 a fee-bearing {@code HOLDER_TRANSFER}
   * Umbuchung books a single {@code CARTEL} account leg of {@code -fee}, which nets to {@code
   * -transfer_fee} too; a fee-free Umbuchung books no account leg at all (so it never appears here)
   * and legacy fee-free rows stay sound. The holder side is checked by {@code
   * BankHolderPostingRepository.findHolderMovementTransactionsWithNonZeroSum}.
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
   * Integrity check (REQ-BANK-020): ids of {@code REVERSAL} transactions whose combined account
   * legs with the reversed transaction do not cancel per account — the account-side negated mirror
   * (ADR-0010/0039), so the union of both transactions' account legs nets to zero for every account
   * they touch. The holder-side mirror is checked by {@code
   * BankHolderPostingRepository.findReversalTransactionsNotMirroredOnHolderLedger}.
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
   * Integrity check (REQ-BANK-020): ids of audited transactions that lack their mandatory audit row
   * (REQ-BANK-012). {@code WIPE_RESET} transactions are excluded by design — a wipe writes a single
   * summarizing {@code WIPE_RESET_EXECUTED} event for the whole run, not one audit row per
   * generated {@code WIPE_RESET} transaction; every other transaction type carries its own audit
   * row that references it.
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
}
