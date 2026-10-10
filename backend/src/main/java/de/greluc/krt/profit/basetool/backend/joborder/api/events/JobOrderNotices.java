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

package de.greluc.krt.profit.basetool.backend.joborder.api.events;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The notification events of the job-order module beyond creation (REQ-ORDERS-041…044), built from
 * scalars so they can be delivered after the commit.
 */
public final class JobOrderNotices {

  /** The entity tag of an order that is gone, so its notice has no page to link. */
  public static final String ENTITY_TYPE_DELETED = "JOB_ORDER_DELETED";

  private static final String UNKNOWN = ActorRef.UNKNOWN_NAME;

  private JobOrderNotices() {}

  private static String text(@Nullable String value) {
    return value == null || value.isBlank() ? UNKNOWN : value.trim();
  }

  private static Map<String, String> params(@Nullable Integer displayId, @Nullable String handle) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("displayId", displayId == null ? UNKNOWN : displayId.toString());
    params.put("handle", text(handle));
    return params;
  }

  /**
   * An order moved to another responsible unit; the earlier creation notices go.
   *
   * @param jobOrderId the order
   * @param displayId its display id
   * @param handle its contact handle
   * @param from the previous unit's shorthand
   * @param to the new unit
   * @param toShorthand the new unit's shorthand
   * @param actor who moved it
   * @return the event
   */
  @NotNull
  public static NoticeEvent reassigned(
      @NotNull UUID jobOrderId,
      @Nullable Integer displayId,
      @Nullable String handle,
      @Nullable String from,
      @NotNull OrgUnitRef to,
      @Nullable String toShorthand,
      @NotNull ActorRef actor) {
    Map<String, String> params = params(displayId, handle);
    params.put("from", text(from));
    params.put("to", text(toShorthand));
    return NoticeEvent.builder()
        .eventType(NotificationEventType.JOB_ORDER_REASSIGNED)
        .actorSub(actor.id())
        .entityType(JobOrderCreatedEvent.ENTITY_TYPE)
        .entityId(jobOrderId)
        .contextOrgUnits(Map.of(NotificationContextRole.RESPONSIBLE, to))
        .renderParams(params)
        .resolvesNotificationTypes(
            Set.of(
                NotificationType.JOB_ORDER_CREATED,
                NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER,
                NotificationType.JOB_ORDER_REASSIGNED))
        .build();
  }

  /**
   * An order was completed, rejected or deleted; its requesting unit's leadership is told.
   *
   * @param jobOrderId the order
   * @param displayId its display id
   * @param handle its contact handle
   * @param requesting the requesting unit, or {@code null}
   * @param requestingShorthand the requesting unit's shorthand
   * @param statusCode {@code COMPLETED}, {@code REJECTED} or {@code DELETED}
   * @param actor who closed it
   * @return the event
   */
  @NotNull
  public static NoticeEvent finished(
      @NotNull UUID jobOrderId,
      @Nullable Integer displayId,
      @Nullable String handle,
      @Nullable OrgUnitRef requesting,
      @Nullable String requestingShorthand,
      @NotNull String statusCode,
      @NotNull ActorRef actor) {
    Map<String, String> params = params(displayId, handle);
    params.put("unit", text(requestingShorthand));
    params.put("statusCode", statusCode);
    NoticeEvent.NoticeEventBuilder builder =
        NoticeEvent.builder()
            .eventType(NotificationEventType.JOB_ORDER_FINISHED)
            .actorSub(actor.id())
            .entityType(
                "DELETED".equals(statusCode)
                    ? ENTITY_TYPE_DELETED
                    : JobOrderCreatedEvent.ENTITY_TYPE)
            .entityId(jobOrderId)
            .renderParams(params);
    if (requesting != null) {
      builder.contextOrgUnits(Map.of(NotificationContextRole.REQUESTING, requesting));
    }
    return builder.build();
  }

  /**
   * A member was assigned to an order; a removal notice for them goes.
   *
   * @param jobOrderId the order
   * @param displayId its display id
   * @param handle its contact handle
   * @param assigneeId the assigned member, the single recipient
   * @param actor who assigned them
   * @return the event
   */
  @NotNull
  public static NoticeEvent assigneeAdded(
      @NotNull UUID jobOrderId,
      @Nullable Integer displayId,
      @Nullable String handle,
      @NotNull UUID assigneeId,
      @NotNull ActorRef actor) {
    Map<String, String> params = params(displayId, handle);
    params.put("actor", text(actor.name()));
    return NoticeEvent.builder()
        .eventType(NotificationEventType.JOB_ORDER_ASSIGNEE_ADDED)
        .actorSub(actor.id())
        .entityType(JobOrderCreatedEvent.ENTITY_TYPE)
        .entityId(jobOrderId)
        .contextRecipientUserId(assigneeId)
        .renderParams(params)
        .resolvesNotificationTypesForRecipients(Set.of(NotificationType.JOB_ORDER_ASSIGNED))
        .supersedeRecipients(Set.of(assigneeId))
        .build();
  }

  /**
   * A member was removed from an order's assignees; their assignment notice goes.
   *
   * @param jobOrderId the order
   * @param assigneeId the removed member
   * @param actor who removed them
   * @return the event
   */
  @NotNull
  public static NoticeEvent assigneeRemoved(
      @NotNull UUID jobOrderId, @NotNull UUID assigneeId, @NotNull ActorRef actor) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.JOB_ORDER_ASSIGNEE_REMOVED)
        .actorSub(actor.id())
        .entityType(JobOrderCreatedEvent.ENTITY_TYPE)
        .entityId(jobOrderId)
        .resolvesNotificationTypesForRecipients(Set.of(NotificationType.JOB_ORDER_ASSIGNED))
        .supersedeRecipients(Set.of(assigneeId))
        .build();
  }

  /**
   * A material claim was withdrawn by an order edit or a de-escalation.
   *
   * @param jobOrderId the order
   * @param displayId its display id
   * @param handle its contact handle
   * @param claimantId the member who made the claim, the single recipient
   * @param material the claimed material's name
   * @param reasonCode {@code ORDER_CHANGED} or {@code DE_ESCALATED}
   * @param actor who changed the order
   * @return the event
   */
  @NotNull
  public static NoticeEvent claimWithdrawn(
      @NotNull UUID jobOrderId,
      @Nullable Integer displayId,
      @Nullable String handle,
      @NotNull UUID claimantId,
      @Nullable String material,
      @NotNull String reasonCode,
      @NotNull ActorRef actor) {
    Map<String, String> params = params(displayId, handle);
    params.put("material", text(material));
    params.put("reasonCode", reasonCode);
    return NoticeEvent.builder()
        .eventType(NotificationEventType.JOB_ORDER_CLAIM_WITHDRAWN)
        .actorSub(actor.id())
        .entityType(JobOrderCreatedEvent.ENTITY_TYPE)
        .entityId(jobOrderId)
        .contextRecipientUserId(claimantId)
        .renderParams(params)
        .build();
  }
}
