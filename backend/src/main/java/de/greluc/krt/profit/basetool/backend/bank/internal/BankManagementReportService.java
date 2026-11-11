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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates a bounded management snapshot, detached PDF rendering and its audit event. */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(propagation = Propagation.NEVER)
public class BankManagementReportService {
  private final BankReportSnapshotReader snapshotReader;
  private final BankReportRenderer renderer;
  private final BankExportProperties exportProperties;
  private final BankAuditService bankAuditService;
  private final TransactionTemplate transactionTemplate;

  /**
   * Generates the rolling three-month report from one repeatable-read database snapshot.
   *
   * @param userZone rendering zone, or UTC when absent
   * @return a reopenable PDF; its request owner must close it after response conversion
   */
  public @NotNull BankPdfExport generateThreeMonthReport(@Nullable ZoneId userZone) {
    Instant to = Instant.now();
    Instant from = ZonedDateTime.ofInstant(to, ZoneOffset.UTC).minusMonths(3).toInstant();
    return BankReportSpool.export(
        exportProperties,
        spool -> {
          BankReportSnapshotReader.Management snapshot = snapshotReader.management(spool, from, to);
          renderer.management(spool, snapshot, from, to, userZone);
          transactionTemplate.executeWithoutResult(_ ->
              bankAuditService.record(
                  BankAuditEventType.MANAGEMENT_REPORT_EXPORTED,
                  null,
                  null,
                  null,
                  "period=" + from + ".." + to + ", accounts=" + snapshot.accounts()));
          log.info("Bank three-month report exported ({} accounts)", snapshot.accounts());
        });
  }
}
