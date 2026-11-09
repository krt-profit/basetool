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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles unchanged legacy promises before application readiness under hierarchy coordination.
 */
@Component
@RequiredArgsConstructor
public class JobOrderEligibilityStartup implements ApplicationRunner {

  private static final int ORDER_BATCH_SIZE = 200;

  private final ProfitHierarchyService profitHierarchy;
  private final MaterialClaimRepository claims;
  private final JobOrderEligibilityMaintenance maintenance;

  /**
   * Ends ineligible active promises through normal audited maintenance without changing provenance.
   *
   * @param arguments unused startup arguments
   */
  @Override
  @Transactional
  public void run(ApplicationArguments arguments) {
    profitHierarchy.lockForHierarchyChange();
    UUID afterOrderId = null;
    List<UUID> batch;
    do {
      batch =
          claims.findActivePromiseOrderIdsAfter(afterOrderId, PageRequest.of(0, ORDER_BATCH_SIZE));
      batch.forEach(maintenance::reconcileExistingPromises);
      if (!batch.isEmpty()) {
        afterOrderId = batch.getLast();
      }
    } while (batch.size() == ORDER_BATCH_SIZE);
  }
}
