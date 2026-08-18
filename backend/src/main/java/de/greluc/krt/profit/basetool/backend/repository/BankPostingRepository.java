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

import de.greluc.krt.profit.basetool.backend.model.BankPosting;
import de.greluc.krt.profit.basetool.backend.model.projection.BankAccountBalance;
import de.greluc.krt.profit.basetool.backend.model.projection.BankBookingRow;
import de.greluc.krt.profit.basetool.backend.model.projection.BankCounterLeg;
import de.greluc.krt.profit.basetool.backend.model.projection.BankPostingSlice;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Spring Data repository for the append-only {@link BankPosting} account-ledger legs (epic #556,
 * ADR-0010/0039). Strictly insert-and-read: no {@code @Modifying} method may ever appear here — the
 * ledger is never updated or deleted (REQ-BANK-004, pinned by {@code ArchitectureTest}). Since
 * ADR-0039 a posting carries only the account dimension; the holder dimension lives in {@link
 * BankHolderPostingRepository}. All account balances are computed here as grouped sums
 * (compute-on-read, REQ-BANK-020), backed by the V153 composite indexes.
 */
@Repository
public interface BankPostingRepository extends JpaRepository<BankPosting, UUID> {

  /**
   * The account balance: signed sum of all posting amounts (ADR-0010). Zero for an account without
   * postings.
   *
   * @param accountId the account
   * @return the current balance, never {@code null}
   */
  @Query("SELECT COALESCE(SUM(p.amount), 0) FROM BankPosting p WHERE p.account.id = :accountId")
  BigDecimal accountBalance(@Param("accountId") UUID accountId);

  /**
   * Balances of many accounts in ONE grouped statement — the dashboard and the management list
   * (REQ-BANK-016, REQ-DATA-003: no per-account N+1). Accounts without postings produce no row.
   *
   * @param accountIds the accounts to sum
   * @return one balance row per account that has postings
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.BankAccountBalance(p.account.id, SUM(p.amount))
      FROM BankPosting p WHERE p.account.id IN :accountIds GROUP BY p.account.id
      """)
  List<BankAccountBalance> accountBalances(@Param("accountIds") Collection<UUID> accountIds);

  /**
   * All postings of the given accounts since a cutoff, reduced to the dashboard-relevant columns in
   * ONE statement; the service derives 30-day deltas, in/out totals and the daily sparkline series
   * in memory (REQ-BANK-016).
   *
   * @param accountIds the visible accounts
   * @param cutoff window start (inclusive)
   * @return the posting slices inside the window
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.BankPostingSlice(p.account.id, p.createdAt, p.amount)
      FROM BankPosting p WHERE p.account.id IN :accountIds AND p.createdAt >= :cutoff
      """)
  List<BankPostingSlice> postingSlicesSince(
      @Param("accountIds") Collection<UUID> accountIds, @Param("cutoff") Instant cutoff);

  /**
   * One account's posting slices inside a closed period, reduced to the (createdAt, amount) columns
   * the balance-over-time chart needs (REQ-BANK-049). The service adds the opening balance from
   * {@link #accountBalanceBefore(UUID, Instant)} and derives the end-of-day series in memory
   * ({@code BankBalanceSeriesCalculator}), mirroring the 30-day sparkline math. Both bounds are
   * inclusive.
   *
   * @param accountId the account
   * @param from inclusive period start
   * @param to inclusive period end
   * @return the account's posting slices inside {@code [from, to]}
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.BankPostingSlice(p.account.id, p.createdAt, p.amount)
      FROM BankPosting p
      WHERE p.account.id = :accountId AND p.createdAt >= :from AND p.createdAt <= :to
      """)
  List<BankPostingSlice> postingSlicesInRange(
      @Param("accountId") UUID accountId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * One page of an account's booking history — the account posting joined with its header in a
   * single statement (REQ-BANK-018); newest first by default (whitelisted sort on {@code
   * createdAt}). Since ADR-0039 a {@code TRANSFER} always spans two different accounts, so an
   * account has at most one leg per transaction — no intra-account pair collapse is needed. The
   * holder annotation is resolved separately from the holder ledger (see {@code
   * BankAccountService}).
   *
   * <p>Both period bounds are optional (REQ-BANK-051): a {@code null} {@code from} or {@code to}
   * drops that side of the filter, so passing {@code (null, null)} pages the whole history exactly
   * as before the period filter existed. The bounds are inclusive. Each bound is compared for
   * nullness through {@code CAST(:bound AS timestamp)} rather than a bare {@code :bound IS NULL}:
   * PostgreSQL cannot infer the type of an untyped bind parameter in an {@code IS NULL} position
   * and fails the whole statement at plan time with "could not determine data type of parameter" —
   * so the cast is load-bearing, not cosmetic (mirrors {@code AuditEventRepository#findFiltered}).
   *
   * @param accountId the account
   * @param from inclusive lower bound on the booking instant, or {@code null} for no lower bound
   * @param to inclusive upper bound on the booking instant, or {@code null} for no upper bound
   * @param pageable page, size and whitelisted sort
   * @return one page of booking rows inside the (optionally bounded) period
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.BankBookingRow(p.id, t.id, t.type, p.amount, t.note, t.justification, t.staffNote, p.createdAt, rt.id,
      t.transferFee, t.counterpartyHandle, t.counterpartyOrgUnitName)
      FROM BankPosting p JOIN p.transaction t
      LEFT JOIN t.reversedTransaction rt
      WHERE p.account.id = :accountId
      AND (CAST(:from AS timestamp) IS NULL OR p.createdAt >= :from)
      AND (CAST(:to AS timestamp) IS NULL OR p.createdAt <= :to)
      """)
  Page<BankBookingRow> findBookings(
      @Param("accountId") UUID accountId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable pageable);

  /**
   * An account's booking rows inside a period, oldest first — the statement body (REQ-BANK-014).
   *
   * @param accountId the account
   * @param from period start (inclusive)
   * @param to period end (inclusive)
   * @return the period's booking rows in chronological order
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.BankBookingRow(p.id, t.id, t.type, p.amount, t.note, t.justification, t.staffNote, p.createdAt, rt.id,
      t.transferFee, t.counterpartyHandle, t.counterpartyOrgUnitName)
      FROM BankPosting p JOIN p.transaction t
      LEFT JOIN t.reversedTransaction rt
      WHERE p.account.id = :accountId AND p.createdAt >= :from AND p.createdAt <= :to
      ORDER BY p.createdAt ASC, p.id ASC
      """)
  List<BankBookingRow> findBookingsInPeriod(
      @Param("accountId") UUID accountId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * The account balance immediately before an instant — the statement's opening balance
   * (REQ-BANK-014).
   *
   * @param accountId the account
   * @param before exclusive upper bound
   * @return the balance built from all postings strictly before {@code before}, never {@code null}
   */
  @Query(
      """
      SELECT COALESCE(SUM(p.amount), 0) FROM BankPosting p
      WHERE p.account.id = :accountId AND p.createdAt < :before
      """)
  BigDecimal accountBalanceBefore(
      @Param("accountId") UUID accountId, @Param("before") Instant before);

  /**
   * The account legs of the given transactions with their account labels, batched in one IN-query —
   * counter-account resolution for transfer rows and the negated account-side mirror of a reversal
   * (ADR-0010/0039). The holder side is mirrored via {@link
   * BankHolderPostingRepository#findHolderLegsByTransactionIds}.
   *
   * @param transactionIds the batch of transaction ids
   * @return every account leg of the given transactions
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.BankCounterLeg(t.id, p.id, a.id, a.accountNo, a.name, p.amount)
      FROM BankPosting p JOIN p.transaction t JOIN p.account a
      WHERE t.id IN :transactionIds
      """)
  List<BankCounterLeg> findLegsByTransactionIds(
      @Param("transactionIds") Collection<UUID> transactionIds);

  /**
   * Number of postings on one account — the detail page's facts strip ("Buchungen").
   *
   * @param accountId the account
   * @return the posting count
   */
  long countByAccountId(UUID accountId);

  /**
   * Integrity check (REQ-BANK-020): ids of accounts whose total balance is negative — an invariant
   * the no-overdraft guard (REQ-BANK-006) should make impossible. The holder dimension is
   * deliberately allowed to be negative (ADR-0039) and is therefore not checked.
   *
   * @return the violating account ids (empty when sound)
   */
  @Query("SELECT p.account.id FROM BankPosting p GROUP BY p.account.id HAVING SUM(p.amount) < 0")
  List<UUID> findAccountIdsWithNegativeBalance();
}
