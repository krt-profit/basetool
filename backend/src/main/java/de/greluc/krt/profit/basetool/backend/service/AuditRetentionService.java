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
 * Bounds how long the activity and bank audit trails are kept (REQ-AUDIT-006).
 *
 * <p>Every audit row carries a denormalised actor-handle snapshot that deliberately survives the
 * user's deletion (REQ-AUDIT-001) — which is what makes the trail useful after a member leaves, and
 * also what makes an unbounded trail a permanent record of a named person. The organisation is
 * under no legal obligation to retain these, so "keep forever" had nothing holding it up except the
 * absence of anything that removed them: REQ-AUDIT-004's purge is a manual admin action, and the
 * repositories said so in as many words — <em>there is no automatic retention sweep</em>.
 *
 * <p>This is that sweep. It reuses the manual purge rather than issuing its own deletes, so the two
 * paths cannot diverge in what they remove or in the {@code *_AUDIT_PURGED} marker they leave
 * behind, and so an automatic purge is as visible in the trail as a deliberate one.
 *
 * <p><b>Each domain is purged in its own transaction</b> — {@code purgeBefore} opens one per call —
 * so one domain that cannot be purged does not roll back the domains already done, and a sweep
 * interrupted halfway keeps its committed work.
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
   * <p>Each domain is asked first whether it holds anything that old. That guard is not an
   * optimisation: {@code purgeBefore} records its marker event unconditionally, which is correct
   * for an admin who deliberately purged and found nothing, and wrong for a job that runs every day
   * — without the guard this sweep would mint ten marker rows a day forever, growing the very table
   * it exists to bound.
   *
   * <p>Failures are per domain and never abort the run: one area that cannot be purged is logged
   * and left for the next sweep.
   *
   * @param cutoff purge audit rows that occurred strictly before this instant
   * @return the total number of audit rows deleted this run, across all domains and the bank trail
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
