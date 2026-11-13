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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderPatchWipe;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the job-order trail's one summary event of a patch wipe (REQ-PATCH-017); single orders
 * are not audited one by one.
 */
@Service
@RequiredArgsConstructor
public class JobOrderPatchWipeService implements JobOrderPatchWipe {

  private final AuditRecorder auditRecorder;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordStockLoss(
      @NotNull Collection<UUID> orderIds, @NotNull UUID patchChangeId, @NotNull String patchLabel) {
    auditRecorder.record(
        AuditEventType.JOB_ORDERS_STOCK_WIPED_BY_PATCH,
        patchChangeId,
        patchLabel,
        null,
        AuditDetails.of("jobOrders", new HashSet<>(orderIds).size())
            .with("patchChange", patchChangeId));
  }
}
