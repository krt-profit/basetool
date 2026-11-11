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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates a bounded statement snapshot, detached PDF rendering and the export audit event. */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.NEVER)
public class BankStatementReportService {
  private final BankReportSnapshotReader snapshotReader;
  private final BankReportRenderer renderer;
  private final BankExportProperties exportProperties;
  private final BankAuditService bankAuditService;
  private final TransactionTemplate transactionTemplate;

  /**
   * Generates the staff statement and records a successful export after rendering.
   *
   * @param accountId the account
   * @param from inclusive period start
   * @param to inclusive period end
   * @param userZone rendering zone, or UTC when absent
   * @return a reopenable file-backed PDF owned by the response lifecycle
   */
  public @NotNull BankPdfExport generateStatement(
      @NotNull UUID accountId,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable ZoneId userZone) {
    return generateStatement(accountId, from, to, userZone, false);
  }

  /**
   * Generates a complete chronological statement without retaining all rows or PDF bytes in heap.
   *
   * @param accountId the account
   * @param from inclusive period start
   * @param to inclusive period end
   * @param userZone rendering zone, or UTC when absent
   * @param redactHolders whether holder identities and staff notes are omitted
   * @return a reopenable PDF; its request owner must close it after response conversion
   */
  public @NotNull BankPdfExport generateStatement(
      @NotNull UUID accountId,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable ZoneId userZone,
      boolean redactHolders) {
    return BankReportSpool.export(
        exportProperties,
        spool -> {
          BankReportSpool.Section section =
              snapshotReader.statement(spool, accountId, from, to, redactHolders);
          renderer.statement(spool, section, from, to, userZone, redactHolders);
          transactionTemplate.executeWithoutResult(_ ->
              bankAuditService.record(
                  BankAuditEventType.STATEMENT_EXPORTED,
                  accountId,
                  null,
                  null,
                  AuditDetails.of("period", from + ".." + to)));
          log.info(
              "Bank statement exported for account {} ({} rows{})",
              section.account().accountNo(),
              section.rows(),
              redactHolders ? ", holders redacted" : "");
        });
  }
}
