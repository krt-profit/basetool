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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAssignee;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderNotices;
import de.greluc.krt.profit.basetool.backend.joborder.api.events.JobOrderUpdatedByRequesterEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationRevision;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Captures current order parties as scalar notification context. */
final class JobOrderLifecycleNotices {

  private JobOrderLifecycleNotices() {}

  static @NotNull NotificationRevision revision(@NotNull JobOrder order) {
    return new NotificationRevision(order.getLifecycleVersion(), order.getResponsibilityVersion());
  }

  static @NotNull NoticeEvent finished(
      @NotNull JobOrder order, @NotNull String statusCode, @Nullable UUID actor) {
    OrgUnit namedUnit = order.getRequestingOrgUnit();
    if (namedUnit == null) {
      namedUnit = order.getResponsibleOrgUnit();
    }
    return JobOrderNotices.finished(
        order.getId(),
        order.getDisplayId(),
        order.getHandle(),
        units(order),
        namedUnit == null ? null : namedUnit.getShorthand(),
        statusCode,
        assignees(order),
        actor);
  }

  static @NotNull NoticeEvent reassigned(
      @NotNull JobOrder order, @Nullable OrgUnit previous, @Nullable UUID actor) {
    Map<NotificationContextRole, OrgUnitRef> context = units(order);
    addUnit(context, NotificationContextRole.PREVIOUS_RESPONSIBLE, previous);
    return JobOrderNotices.reassigned(
        order.getId(),
        order.getDisplayId(),
        order.getHandle(),
        previous == null ? null : previous.getShorthand(),
        order.getResponsibleOrgUnit().getShorthand(),
        context,
        revision(order),
        actor);
  }

  static @NotNull NoticeEvent needChanged(@NotNull JobOrder order, @Nullable UUID actor) {
    return JobOrderNotices.needChanged(
        order.getId(),
        order.getDisplayId(),
        order.getHandle(),
        units(order),
        revision(order),
        actor);
  }

  static NotificationEvent needChanged(JobOrder order, UUID actor, boolean responsible) {
    return responsible ? needChanged(order, actor) : requesterChanged(order, actor);
  }

  static JobOrderUpdatedByRequesterEvent requesterChanged(JobOrder order, @Nullable UUID actor) {
    OrgUnit responsible = order.getResponsibleOrgUnit();
    OrgUnit requesting = order.getRequestingOrgUnit();
    return new JobOrderUpdatedByRequesterEvent(
        order.getId(),
        order.getDisplayId(),
        order.getHandle(),
        new OrgUnitRef(responsible.getId(), responsible.getKind()),
        responsible.getShorthand(),
        requesting == null ? null : new OrgUnitRef(requesting.getId(), requesting.getKind()),
        requesting == null ? null : requesting.getShorthand(),
        actor,
        revision(order));
  }

  static @NotNull NoticeEvent reopened(@NotNull JobOrder order, @Nullable UUID actor) {
    return JobOrderNotices.reopened(
        order.getId(),
        order.getDisplayId(),
        order.getHandle(),
        units(order),
        assignees(order),
        revision(order),
        actor);
  }

  static @NotNull NoticeEvent clarificationRequired(@NotNull JobOrder order, @Nullable UUID actor) {
    return JobOrderNotices.clarificationRequired(
        order.getId(),
        order.getDisplayId(),
        order.getHandle(),
        units(order),
        assignees(order),
        revision(order),
        actor);
  }

  static @NotNull NoticeEvent clarificationResolved(@NotNull JobOrder order, @Nullable UUID actor) {
    return JobOrderNotices.clarificationResolved(order.getId(), actor);
  }

  private static Map<NotificationContextRole, OrgUnitRef> units(JobOrder order) {
    Map<NotificationContextRole, OrgUnitRef> context = new EnumMap<>(NotificationContextRole.class);
    addUnit(context, NotificationContextRole.RESPONSIBLE, order.getResponsibleOrgUnit());
    addUnit(context, NotificationContextRole.REQUESTING, order.getRequestingOrgUnit());
    return context;
  }

  private static void addUnit(
      Map<NotificationContextRole, OrgUnitRef> context,
      NotificationContextRole role,
      @Nullable OrgUnit unit) {
    if (unit != null) {
      context.put(role, new OrgUnitRef(unit.getId(), unit.getKind()));
    }
  }

  private static Set<UUID> assignees(JobOrder order) {
    return order.getAssignees().stream()
        .map(JobOrderAssignee::getUser)
        .filter(Objects::nonNull)
        .filter(User::isActiveAccount)
        .map(User::getId)
        .collect(Collectors.toUnmodifiableSet());
  }
}
