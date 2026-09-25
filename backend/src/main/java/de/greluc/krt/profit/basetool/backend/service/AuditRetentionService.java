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

import de.greluc.krt.profit.basetool.backend.model.AuditDomain;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Bounds how long the activity and bank audit trails are kept by periodically purging old rows
 * (REQ-AUDIT-006).
 *
 * <p>Reuses the manual admin purge, so an automatic purge leaves the same {@code *_AUDIT_PURGED}
 * marker. Each domain is purged in its own transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditRetentionService {

  private final AuditService auditService;
  private final BankAuditService bankAuditService;
  private final AuditEventRepository auditEventRepository;
  private final BankAuditEventRepository bankAuditEventRepository;

  /**
   * Purges every activity-audit domain and the bank audit trail of rows older than the cutoff.
   *
   * <p>Domains holding no such rows are skipped, so no empty purge marker is written. A failing
   * domain is logged and does not abort the run.
   *
   * @param cutoff purge audit rows that occurred strictly before this instant
   * @return the total number of audit rows deleted across all domains and the bank trail
   */
  public int purgeOlderThan(@NotNull Instant cutoff) {
    int total = 0;
    for (AuditDomain domain : AuditDomain.values()) {
      if (!auditEventRepository.existsByDomainAndOccurredAtBefore(domain, cutoff)) {
        continue;
      }
      try {
        total += auditService.purgeBefore(domain, cutoff);
      } catch (RuntimeException e) {
        log.warn("Retention: could not purge audit domain {}: {}", domain, e.toString());
      }
    }
    if (bankAuditEventRepository.existsByOccurredAtBefore(cutoff)) {
      try {
        total += bankAuditService.purgeBefore(cutoff);
      } catch (RuntimeException e) {
        log.warn("Retention: could not purge the bank audit trail: {}", e.toString());
      }
    }
    return total;
  }
}
