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

package de.greluc.krt.profit.basetool.backend.purchasing.api.events;

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The purchasing notices (REQ-PUR-024), built from scalars so they can be delivered after the
 * commit. They carry names, numbers and codes only, never a partner, a price or a note
 * (REQ-PUR-025); words that differ by language travel as codes (REQ-NOTIF-028).
 */
public final class PurchasingNotices {

  /** Loose entity-type tag of the notices about one need. */
  public static final String NEED_ENTITY_TYPE = "PURCHASING_NEED";

  /** Loose entity-type tag of the notices about one deal. */
  public static final String DEAL_ENTITY_TYPE = "PURCHASING_DEAL";

  /** Loose entity-type tag of the notices about one pause, global or of one unit. */
  public static final String PAUSE_ENTITY_TYPE = "PURCHASING_PAUSE";

  /** Loose entity-type tag of the notices about one delegate appointment. */
  public static final String DELEGATE_ENTITY_TYPE = "PURCHASING_DELEGATE";

  /** The pause code of a pause for the whole organisation. */
  public static final String SCOPE_GLOBAL = "GLOBAL";

  /** The pause code of a pause for the needs of one unit. */
  public static final String SCOPE_UNIT = "UNIT";

  /** The id the notices about the global pause use as their entity. */
  public static final UUID GLOBAL_PAUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000a0001");

  private PurchasingNotices() {}

  /**
   * A need was released for purchase; the active delegates are told.
   *
   * @param needId the need
   * @param unitName the raising unit's name
   * @param entry the material or item name
   * @param amount the amount asked for
   * @param delegates the active delegates
   * @param actorSub the member who released the need, kept out of the recipients
   * @return the event
   */
  @NotNull
  public static NoticeEvent needReleased(
      @NotNull UUID needId,
      @NotNull String unitName,
      @NotNull String entry,
      @NotNull String amount,
      @NotNull Set<UUID> delegates,
      @Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.PURCHASING_NEED_RELEASED)
        .actorSub(actorSub)
        .entityType(NEED_ENTITY_TYPE)
        .entityId(needId)
        .contextRecipientUserIds(delegates)
        .renderParams(Map.of("unit", unitName, "entry", entry, "amount", amount))
        .build();
  }

  /**
   * A purchasable need was fulfilled or withdrawn; its release notices go.
   *
   * @param needId the need
   * @return the event
   */
  @NotNull
  public static NoticeEvent needClosed(@NotNull UUID needId) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.PURCHASING_NEED_CLOSED)
        .entityType(NEED_ENTITY_TYPE)
        .entityId(needId)
        .resolvesNotificationTypes(Set.of(NotificationType.PURCHASING_NEED_RELEASED))
        .build();
  }

  /**
   * A delegate recorded a delivery; the lead and the Logisticians of the raising unit are told.
   *
   * @param dealId the deal
   * @param dealNo the deal's running number
   * @param unitId the raising unit
   * @param unitKind the raising unit's kind
   * @param unitName the raising unit's name
   * @param entry the material or item name
   * @param amount the delivered amount
   * @param actorSub the delegate
   * @return the event
   */
  @NotNull
  public static NoticeEvent deliveryRecorded(
      @NotNull UUID dealId,
      long dealNo,
      @NotNull UUID unitId,
      @NotNull OrgUnitKind unitKind,
      @NotNull String unitName,
      @NotNull String entry,
      @NotNull String amount,
      @Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.PURCHASING_DELIVERY_RECORDED)
        .actorSub(actorSub)
        .entityType(DEAL_ENTITY_TYPE)
        .entityId(dealId)
        .contextOrgUnits(
            Map.of(NotificationContextRole.RESPONSIBLE, new OrgUnitRef(unitId, unitKind)))
        .renderParams(
            Map.of(
                "deal", Long.toString(dealNo), "unit", unitName, "entry", entry, "amount", amount))
        .build();
  }

  /**
   * The raising unit decided a delivery; the deal's delegate is told and the acceptance notice
   * goes.
   *
   * @param dealId the deal
   * @param dealNo the deal's running number
   * @param delegateId the deal's delegate, or {@code null} once erased
   * @param outcomeCode {@code ACCEPTED}, {@code PARTIALLY_ACCEPTED} or {@code REJECTED}
   * @param actorSub the deciding member
   * @return the event
   */
  @NotNull
  public static NoticeEvent deliveryDecided(
      @NotNull UUID dealId,
      long dealNo,
      @Nullable UUID delegateId,
      @NotNull String outcomeCode,
      @Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.PURCHASING_DELIVERY_DECIDED)
        .actorSub(actorSub)
        .entityType(DEAL_ENTITY_TYPE)
        .entityId(dealId)
        .contextRecipientUserId(delegateId)
        .renderParams(Map.of("deal", Long.toString(dealNo), "outcomeCode", outcomeCode))
        .resolvesNotificationTypes(Set.of(NotificationType.PURCHASING_DELIVERY_RECORDED))
        .build();
  }

  /**
   * A deal's settlement is past its due date; the delegate, the governing leadership and the bank
   * management are told.
   *
   * @param dealId the deal
   * @param dealNo the deal's running number
   * @param delegateId the deal's delegate, or {@code null} once erased
   * @param governingLeadership the Bereichsleiter and Bereichskoordinatoren of every governing
   *     Bereich
   * @return the event
   */
  @NotNull
  public static NoticeEvent settlementOverdue(
      @NotNull UUID dealId,
      long dealNo,
      @Nullable UUID delegateId,
      @NotNull Set<UUID> governingLeadership) {
    Set<UUID> recipients = new HashSet<>(governingLeadership);
    if (delegateId != null) {
      recipients.add(delegateId);
    }
    return NoticeEvent.builder()
        .eventType(NotificationEventType.PURCHASING_SETTLEMENT_OVERDUE)
        .entityType(DEAL_ENTITY_TYPE)
        .entityId(dealId)
        .contextRecipientUserIds(recipients)
        .renderParams(Map.of("deal", Long.toString(dealNo)))
        .build();
  }

  /**
   * Purchasing was paused; the active delegates and, for a unit pause, the unit's leadership are
   * told.
   *
   * @param entityId the unit for a unit pause, else {@link #GLOBAL_PAUSE_ID}
   * @param unitId the paused raising unit, or {@code null} for a global pause
   * @param unitKind the unit's kind, or {@code null} for a global pause
   * @param unitName the unit's name, or {@code null} for a global pause
   * @param reasonCode the pause reason code
   * @param delegates the active delegates
   * @param actorSub the member who paused
   * @return the event
   */
  @NotNull
  public static NoticeEvent paused(
      @NotNull UUID entityId,
      @Nullable UUID unitId,
      @Nullable OrgUnitKind unitKind,
      @Nullable String unitName,
      @NotNull String reasonCode,
      @NotNull Set<UUID> delegates,
      @Nullable UUID actorSub) {
    return pauseEvent(
        NotificationEventType.PURCHASING_PAUSED,
        entityId,
        unitId,
        unitKind,
        unitName,
        reasonCode,
        delegates,
        actorSub,
        Set.of());
  }

  /**
   * Purchasing was resumed; the pause notices go and the same people are told.
   *
   * @param entityId the unit for a unit pause, else {@link #GLOBAL_PAUSE_ID}
   * @param unitId the resumed raising unit, or {@code null} for the global resume
   * @param unitKind the unit's kind, or {@code null} for the global resume
   * @param unitName the unit's name, or {@code null} for the global resume
   * @param delegates the active delegates
   * @param actorSub the member who resumed
   * @return the event
   */
  @NotNull
  public static NoticeEvent resumed(
      @NotNull UUID entityId,
      @Nullable UUID unitId,
      @Nullable OrgUnitKind unitKind,
      @Nullable String unitName,
      @NotNull Set<UUID> delegates,
      @Nullable UUID actorSub) {
    return pauseEvent(
        NotificationEventType.PURCHASING_RESUMED,
        entityId,
        unitId,
        unitKind,
        unitName,
        null,
        delegates,
        actorSub,
        Set.of(NotificationType.PURCHASING_PAUSED));
  }

  private static NoticeEvent pauseEvent(
      NotificationEventType type,
      UUID entityId,
      UUID unitId,
      OrgUnitKind unitKind,
      String unitName,
      String reasonCode,
      Set<UUID> delegates,
      UUID actorSub,
      Set<NotificationType> resolves) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("scopeCode", unitId == null ? SCOPE_GLOBAL : SCOPE_UNIT);
    if (unitName != null) {
      params.put("unit", unitName);
    }
    if (reasonCode != null) {
      params.put("reasonCode", reasonCode);
    }
    Map<NotificationContextRole, OrgUnitRef> contexts =
        unitId == null || unitKind == null
            ? Map.of()
            : Map.of(NotificationContextRole.RESPONSIBLE, new OrgUnitRef(unitId, unitKind));
    return NoticeEvent.builder()
        .eventType(type)
        .actorSub(actorSub)
        .entityType(PAUSE_ENTITY_TYPE)
        .entityId(entityId)
        .contextRecipientUserIds(delegates)
        .contextOrgUnits(contexts)
        .renderParams(params)
        .resolvesNotificationTypes(resolves)
        .build();
  }

  /**
   * A member was appointed Handelsbeauftragter; the member is told.
   *
   * @param delegateId the appointment
   * @param userId the member
   * @param actorSub the appointing member
   * @return the event
   */
  @NotNull
  public static NoticeEvent delegateAppointed(
      @NotNull UUID delegateId, @NotNull UUID userId, @Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.PURCHASING_DELEGATE_APPOINTED)
        .actorSub(actorSub)
        .entityType(DELEGATE_ENTITY_TYPE)
        .entityId(delegateId)
        .contextRecipientUserId(userId)
        .build();
  }

  /**
   * An appointment was revoked; the member is told and the appointment notice goes.
   *
   * @param delegateId the appointment
   * @param userId the member
   * @param actorSub the revoking member
   * @return the event
   */
  @NotNull
  public static NoticeEvent delegateRevoked(
      @NotNull UUID delegateId, @NotNull UUID userId, @Nullable UUID actorSub) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.PURCHASING_DELEGATE_REVOKED)
        .actorSub(actorSub)
        .entityType(DELEGATE_ENTITY_TYPE)
        .entityId(delegateId)
        .contextRecipientUserId(userId)
        .resolvesNotificationTypes(Set.of(NotificationType.PURCHASING_DELEGATE_APPOINTED))
        .build();
  }
}
