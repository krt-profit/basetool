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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import org.jetbrains.annotations.NotNull;

/** Names the audit event of a status change and its details, which carry no free text. */
final class JobOrderStatusAudit {

  private JobOrderStatusAudit() {}

  /**
   * Builds the audited outcome of a status change: a completion records {@code
   * JOB_ORDER_COMPLETED}, a client's cancel its own {@code JOB_ORDER_CANCELLED_VIA_CLIENT} with the
   * previous status (REQ-AUDIT-001, ADR-0407), every other change {@code JOB_ORDER_STATUS_CHANGED}.
   *
   * @param order the changed order
   * @param previous the status before the change
   * @param status the status after it
   * @param actor who changed it
   * @return the audited outcome without notices
   */
  static @NotNull JobOrderEdit changed(
      @NotNull JobOrder order,
      @NotNull JobOrderStatus previous,
      @NotNull JobOrderStatus status,
      @NotNull JobOrderActor actor) {
    if (status == JobOrderStatus.COMPLETED && previous != JobOrderStatus.COMPLETED) {
      return JobOrderEdit.audited(
          order,
          AuditEventType.JOB_ORDER_COMPLETED,
          AuditDetails.of("from", previous).with("autoCompleted", "false"));
    }
    if (status == JobOrderStatus.CANCELLED && actor.viaClient()) {
      return JobOrderEdit.audited(
          order,
          AuditEventType.JOB_ORDER_CANCELLED_VIA_CLIENT,
          AuditDetails.of("previousStatus", previous));
    }
    return JobOrderEdit.audited(
        order,
        AuditEventType.JOB_ORDER_STATUS_CHANGED,
        AuditDetails.of("from", previous).with("to", status));
  }
}
