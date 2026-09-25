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

import de.greluc.krt.profit.basetool.backend.model.BankHolderPosting;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBalance;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBookingRow;
import de.greluc.krt.profit.basetool.backend.model.projection.BankHolderLeg;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for the append-only {@link BankHolderPosting} holder-ledger legs
 * (ADR-0039, REQ-BANK-004). A holder's balance is the global sum over this table and may be
 * negative (REQ-BANK-003, REQ-BANK-006).
 */
@Repository
public interface BankHolderPostingRepository extends JpaRepository<BankHolderPosting, UUID> {

  /**
   * Returns each holder's global total in one grouped statement, labelled with the linked user's
   * live name or, once the user is gone, the {@code handle} snapshot (REQ-BANK-003). Holders
   * without a leg produce no row.
   *
   * @return per-holder global totals with the live display label
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBalance(h.id,
      CASE WHEN u.displayName IS NOT NULL AND TRIM(u.displayName) <> ''
      THEN u.displayName ELSE COALESCE(u.username, h.handle) END,
      h.active, SUM(p.amount))
      FROM BankHolderPosting p JOIN p.holder h LEFT JOIN h.user u
      GROUP BY h.id, h.active, h.handle, u.displayName, u.username
      """)
  List<BankHolderBalance> holderTotals();

  /**
   * One holder's global custody across the whole bank — the targeted single-holder form of {@link
   * #holderTotals()} for the holder-update / holder-transfer responses.
   *
   * @param holderId the holder
   * @return the holder's global total, never {@code null}
   */
  @Query("SELECT COALESCE(SUM(p.amount), 0) FROM BankHolderPosting p WHERE p.holder.id = :holderId")
  BigDecimal holderTotal(@Param("holderId") UUID holderId);

  /**
   * Probes whether a holder appears on any holder-ledger leg — used by surfaces that must know
   * whether a holder has ledger history.
   *
   * @param holderId the holder
   * @return {@code true} when at least one leg names the holder
   */
  boolean existsByHolderId(UUID holderId);

  /**
   * Returns the holder legs of the given transactions in one IN-query, labelled with the linked
   * user's live name or the {@code handle} snapshot (REQ-BANK-003).
   *
   * @param transactionIds the batch of transaction ids
   * @return every holder leg of the given transactions with the live display label
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.BankHolderLeg(t.id, h.id,
      CASE WHEN u.displayName IS NOT NULL AND TRIM(u.displayName) <> ''
      THEN u.displayName ELSE COALESCE(u.username, h.handle) END,
      p.amount)
      FROM BankHolderPosting p JOIN p.transaction t JOIN p.holder h
      LEFT JOIN h.user u
      WHERE t.id IN :transactionIds
      """)
  List<BankHolderLeg> findHolderLegsByTransactionIds(
      @Param("transactionIds") Collection<UUID> transactionIds);

  /**
   * Returns one page of a holder's custody history, each leg joined with its transaction header,
   * newest first by default (REQ-BANK-032).
   *
   * @param holderId the holder
   * @param pageable page, size and whitelisted sort
   * @return one page of the holder's booking rows
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.BankHolderBookingRow(p.id, t.id, t.type, p.amount, t.note, p.createdAt, rt.id, t.transferFee)
      FROM BankHolderPosting p JOIN p.transaction t
      LEFT JOIN t.reversedTransaction rt WHERE p.holder.id = :holderId
      """)
  Page<BankHolderBookingRow> findHolderBookings(
      @Param("holderId") UUID holderId, Pageable pageable);

  /**
   * Integrity check (REQ-BANK-020, ADR-0052): ids of {@code TRANSFER} / {@code HOLDER_TRANSFER}
   * transactions whose holder legs do not net to {@code -transfer_fee}.
   *
   * @return the violating transaction ids (empty when sound)
   */
  @Query(
      """
      SELECT t.id FROM BankHolderPosting p JOIN p.transaction t
      WHERE t.type IN (
      de.greluc.krt.profit.basetool.backend.model.BankTransactionType.TRANSFER,
      de.greluc.krt.profit.basetool.backend.model.BankTransactionType.HOLDER_TRANSFER)
      GROUP BY t.id, t.transferFee HAVING SUM(p.amount) + t.transferFee <> 0
      """)
  List<UUID> findHolderMovementTransactionsWithNonZeroSum();

  /**
   * Integrity check (REQ-BANK-020): ids of {@code REVERSAL} transactions whose holder legs,
   * combined with the reversed transaction's, do not cancel per holder.
   *
   * @return the violating reversal transaction ids (empty when sound)
   */
  @Query(
      value =
          """
          SELECT rt.id FROM bank_transaction rt
          WHERE rt.type = 'REVERSAL' AND EXISTS (
          SELECT 1 FROM bank_holder_posting p
          WHERE p.transaction_id IN (rt.id, rt.reversed_transaction_id)
          GROUP BY p.holder_id HAVING SUM(p.amount) <> 0)
          """,
      nativeQuery = true)
  List<UUID> findReversalTransactionsNotMirroredOnHolderLedger();
}
