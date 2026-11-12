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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderCreatedEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationCauseGuard;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationRevision;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Checks actionable order events against the state held through Inbox delivery. */
@Service
@RequiredArgsConstructor
class JobOrderActionNoticeGuard implements NotificationCauseGuard {

  private final JobOrderRepository orders;
  private final JobOrderResponsibilityGuard responsibility;
  private final ProfitHierarchyService hierarchy;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean isCurrent(@NotNull NotificationEvent event) {
    if (!JobOrderCreatedEvent.ENTITY_TYPE.equals(event.entityType()) || event.entityId() == null) {
      return true;
    }
    return switch (event.eventType()) {
      case JOB_ORDER_CREATED,
          JOB_ORDER_UPDATED_BY_REQUESTER,
          JOB_ORDER_UPDATED_BY_RESPONSIBLE,
          JOB_ORDER_REASSIGNED,
          JOB_ORDER_REOPENED,
          JOB_ORDER_CLOSED,
          JOB_ORDER_CLARIFICATION_REQUIRED,
          JOB_ORDER_CLARIFICATION_RESOLVED ->
          currentOrderPrompt(event);
      default -> true;
    };
  }

  private boolean currentOrderPrompt(NotificationEvent event) {
    UUID id = event.entityId();
    hierarchy.lockForOrderWrite();
    if (orders.lockForScopedEdit(id).isEmpty()) {
      return event.eventType() == NotificationEventType.JOB_ORDER_CLOSED;
    }
    JobOrder order = orders.getReferenceById(id);
    boolean valid = responsibility.isValid(order);
    boolean active = order.getStatus().isActive();
    boolean sameResponsible = sameResponsible(event, order);
    boolean sameRevision = sameRevision(event, order);
    return switch (event.eventType()) {
      case JOB_ORDER_CREATED, JOB_ORDER_REOPENED ->
          valid && active && sameResponsible && sameRevision;
      case JOB_ORDER_UPDATED_BY_REQUESTER, JOB_ORDER_UPDATED_BY_RESPONSIBLE, JOB_ORDER_REASSIGNED ->
          valid && sameResponsible && sameRevision;
      case JOB_ORDER_CLOSED -> valid && !active && sameRevision;
      case JOB_ORDER_CLARIFICATION_REQUIRED -> !valid && sameResponsible && sameRevision;
      case JOB_ORDER_CLARIFICATION_RESOLVED -> valid;
      default -> throw new IllegalArgumentException("Expected an actionable job-order event");
    };
  }

  private static boolean sameResponsible(NotificationEvent event, JobOrder order) {
    OrgUnitRef expected = event.contextOrgUnits().get(NotificationContextRole.RESPONSIBLE);
    return Objects.equals(
        expected == null ? null : expected.id(), OrgUnit.idOf(order.getResponsibleOrgUnit()));
  }

  private static boolean sameRevision(NotificationEvent event, JobOrder order) {
    NotificationRevision observed = event.observedRevision();
    return observed == null || observed.equals(JobOrderLifecycleNotices.revision(order));
  }
}
