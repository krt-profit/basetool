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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Composes privacy-safe responsibility facts for order creation and transfer audits. */
final class JobOrderResponsibilityAudit {

  private JobOrderResponsibilityAudit() {}

  /**
   * Names the audit event of a create: a client's create records its own type instead of the web's
   * (REQ-AUDIT-001, ADR-0407).
   *
   * @param type the new order's kind
   * @param actor who created it
   * @return {@code JOB_ORDER_CREATED_VIA_CLIENT} for a client, else the web's type of the kind
   */
  static @NotNull AuditEventType createdType(
      @NotNull JobOrderType type, @NotNull JobOrderActor actor) {
    if (actor.viaClient()) {
      return AuditEventType.JOB_ORDER_CREATED_VIA_CLIENT;
    }
    return switch (type) {
      case MATERIAL, AUEC -> AuditEventType.JOB_ORDER_CREATED;
      case ITEM -> AuditEventType.JOB_ORDER_ITEM_CREATED;
    };
  }

  /**
   * Captures a new order's creation details: a client's create records its requesting and
   * responsible unit and its target, a web create the details of {@link #created(JobOrder)}.
   *
   * @param order the persisted order
   * @param actor who created it
   * @param target an aUEC order's target, {@code null} for the other kinds
   * @return its creation details
   */
  static @NotNull AuditDetails created(
      @NotNull JobOrder order, @NotNull JobOrderActor actor, @Nullable BigDecimal target) {
    if (!actor.viaClient()) {
      return created(order);
    }
    return AuditDetails.of("requestingOrgUnit", unitReference(order.getRequestingOrgUnit()))
        .with("responsibleOrgUnit", unitReference(order.getResponsibleOrgUnit()))
        .with("target", target);
  }

  /**
   * Captures a new order's parties, original Profit boundary and requirement count.
   *
   * @param order the persisted order
   * @return its creation details
   */
  static @NotNull AuditDetails created(@NotNull JobOrder order) {
    boolean material = order.getType() == JobOrderType.MATERIAL;
    return AuditDetails.of("type", order.getType())
        .with(
            material ? "materials" : "lines",
            material ? order.getMaterials().size() : order.getItems().size())
        .with("responsibleOrgUnit", unitReference(order.getResponsibleOrgUnit()))
        .with("requestingOrgUnit", unitReference(order.getRequestingOrgUnit()))
        .with("profitOrgUnit", order.getOriginalProfitOrgUnitId())
        .with("priority", order.getPriority());
  }

  /**
   * Captures a transfer without inferring an unknown original Profit boundary.
   *
   * @param order the transferred order
   * @param previous its previous responsible unit
   * @param profitRoot the verified current Profit boundary
   * @return its transfer details
   */
  static @NotNull AuditDetails transferred(
      @NotNull JobOrder order, @Nullable OrgUnit previous, @NotNull UUID profitRoot) {
    return AuditDetails.of("fromOrgUnit", unitReference(previous))
        .with("toOrgUnit", unitReference(order.getResponsibleOrgUnit()))
        .with("profitOrgUnit", profitRoot)
        .with("originalProfitBoundaryUnknown", order.getOriginalProfitOrgUnitId() == null);
  }

  private static @NotNull String unitReference(@Nullable OrgUnit unit) {
    return unit == null ? "-" : unit.getId() + "(" + unit.getKind() + ")";
  }
}
