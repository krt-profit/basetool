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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Current order facts governing fulfillment authority and terminal effects.
 *
 * @param responsibleId the current processing unit
 * @param originalProfitId the verified original hierarchy boundary, or null when unknown
 * @param type the fulfillment kind
 * @param status the current order lifecycle state
 */
public record JobOrderFulfillmentContext(
    @Nullable UUID responsibleId,
    @Nullable UUID originalProfitId,
    @NotNull JobOrderType type,
    @NotNull JobOrderStatus status) {
  @NotNull
  static JobOrderFulfillmentContext of(@NotNull JobOrder order) {
    return new JobOrderFulfillmentContext(
        order.getResponsibleOrgUnit() == null ? null : order.getResponsibleOrgUnit().getId(),
        order.getOriginalProfitOrgUnitId(),
        order.getType(),
        order.getStatus());
  }
}
