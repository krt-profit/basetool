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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the bank's two append-only ledgers' invariants (REQ-BANK-020, ADR-0039/0052): no
 * negative account balance (the holder dimension is intentionally allowed to be negative,
 * REQ-BANK-006, so it is <strong>not</strong> checked), every {@code TRANSFER} account leg pair and
 * every {@code TRANSFER}/{@code HOLDER_TRANSFER} holder leg pair nets to {@code -transfer_fee}
 * (zero when fee-free — a fee-bearing customer-facing {@code TRANSFER} loses the fee to the game,
 * while the internal {@code HOLDER_TRANSFER} Umbuchung is fee-free, ADR-0052; historical ADR-0041
 * fee-bearing Umbuchung rows still satisfy the same {@code -transfer_fee} check), every {@code
 * REVERSAL} is the negated mirror of its original on both ledgers (ADR-0010/0039), and every
 * audited transaction carries its audit row (REQ-BANK-012; {@code WIPE_RESET} is summarized once,
 * not per row). Pure reads — it never mutates the ledger. Violations are reported as {@code ERROR}
 * log lines (carrying the correlation id of the run) so monitoring can alert; the returned {@link
 * IntegrityReport} lets tests and callers inspect the findings.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BankLedgerIntegrityService {

  private final BankPostingRepository bankPostingRepository;
  private final BankHolderPostingRepository bankHolderPostingRepository;
  private final BankTransactionRepository bankTransactionRepository;

  /**
   * The outcome of one integrity sweep: the violating ids per invariant.
   *
   * @param negativeAccountBalances account ids whose total balance is negative
   * @param unbalancedTransfers transfer transaction ids whose account legs do not sum to {@code
   *     -transfer_fee} (zero when fee-free; ADR-0052)
   * @param unbalancedHolderMovements transfer/holder-transfer transaction ids whose holder legs do
   *     not sum to {@code -transfer_fee} (zero when fee-free; ADR-0052)
   * @param brokenReversals reversal transaction ids that are not the negated account-side mirror of
   *     their original
   * @param brokenHolderReversals reversal transaction ids that are not the negated holder-side
   *     mirror of their original
   * @param transactionsWithoutAudit ids of audited transaction types that lack their mandatory
   *     audit row (REQ-BANK-012); {@code WIPE_RESET} is excluded (summarized once, not per row)
   */
  public record IntegrityReport(
      @NotNull List<UUID> negativeAccountBalances,
      @NotNull List<UUID> unbalancedTransfers,
      @NotNull List<UUID> unbalancedHolderMovements,
      @NotNull List<UUID> brokenReversals,
      @NotNull List<UUID> brokenHolderReversals,
      @NotNull List<UUID> transactionsWithoutAudit) {

    /**
     * Whether the ledger is sound (no violation in any category).
     *
     * @return {@code true} when every invariant holds
     */
    public boolean isSound() {
      return negativeAccountBalances.isEmpty()
          && unbalancedTransfers.isEmpty()
          && unbalancedHolderMovements.isEmpty()
          && brokenReversals.isEmpty()
          && brokenHolderReversals.isEmpty()
          && transactionsWithoutAudit.isEmpty();
    }

    /**
     * Total number of distinct violations across all categories.
     *
     * @return the violation count
     */
    public int violationCount() {
      return negativeAccountBalances.size()
          + unbalancedTransfers.size()
          + unbalancedHolderMovements.size()
          + brokenReversals.size()
          + brokenHolderReversals.size()
          + transactionsWithoutAudit.size();
    }
  }

  /**
   * Runs every integrity query and logs each violation at {@code ERROR}. A sound ledger logs one
   * {@code DEBUG} confirmation only.
   *
   * @return the report of all findings
   */
  @Transactional(readOnly = true)
  public @NotNull IntegrityReport verify() {
    List<UUID> negativeAccounts = bankPostingRepository.findAccountIdsWithNegativeBalance();
    List<UUID> unbalancedTransfers =
        bankTransactionRepository.findTransferTransactionsWithNonZeroSum();
    List<UUID> unbalancedHolderMovements =
        bankHolderPostingRepository.findHolderMovementTransactionsWithNonZeroSum();
    List<UUID> brokenReversals = bankTransactionRepository.findReversalTransactionsNotMirrored();
    List<UUID> brokenHolderReversals =
        bankHolderPostingRepository.findReversalTransactionsNotMirroredOnHolderLedger();
    List<UUID> transactionsWithoutAudit =
        bankTransactionRepository.findTransactionsWithoutAuditEvent();

    IntegrityReport report =
        new IntegrityReport(
            negativeAccounts,
            unbalancedTransfers,
            unbalancedHolderMovements,
            brokenReversals,
            brokenHolderReversals,
            transactionsWithoutAudit);

    if (report.isSound()) {
      log.debug("Bank ledger integrity check passed — no violations.");
      return report;
    }

    for (UUID accountId : negativeAccounts) {
      log.error("Bank integrity violation: account {} has a negative balance", accountId);
    }
    for (UUID transactionId : unbalancedTransfers) {
      log.error(
          "Bank integrity violation: transfer/Umbuchung transaction {} account legs do not net to"
              + " -transfer_fee",
          transactionId);
    }
    for (UUID transactionId : unbalancedHolderMovements) {
      log.error(
          "Bank integrity violation: transaction {} holder legs do not sum to zero", transactionId);
    }
    for (UUID transactionId : brokenReversals) {
      log.error(
          "Bank integrity violation: reversal transaction {} is not the negated account-side mirror"
              + " of its original",
          transactionId);
    }
    for (UUID transactionId : brokenHolderReversals) {
      log.error(
          "Bank integrity violation: reversal transaction {} is not the negated holder-side mirror"
              + " of its original",
          transactionId);
    }
    for (UUID transactionId : transactionsWithoutAudit) {
      log.error("Bank integrity violation: transaction {} has no audit row", transactionId);
    }
    log.error("Bank ledger integrity check found {} violation(s).", report.violationCount());
    return report;
  }
}
