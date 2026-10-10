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

package de.greluc.krt.profit.basetool.backend.operation.api.events;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.notification.api.events.OrgUnitRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The notification events of the operation module (REQ-OPERATION-020, -021), built from scalars so
 * they can be delivered after the commit.
 */
public final class OperationNotices {

  /** Loose entity-type tag stored on every operation notification. */
  public static final String ENTITY_TYPE = "OPERATION";

  private OperationNotices() {}

  private static String aUec(@NotNull BigDecimal amount) {
    return amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
  }

  private static String fraction(@NotNull BigDecimal amount) {
    return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /**
   * An operation was completed and its payouts are due.
   *
   * @param operationId the operation
   * @param operationName its name
   * @param owningUnit the unit that owns it, or {@code null} for an unowned operation
   * @param actor who completed it
   * @param total the sum of all payouts
   * @param open the sum of the payouts not paid out yet
   * @param paid how many payouts are paid out
   * @param count how many payouts there are
   * @param unfinished how many of its missions lack an actual start or end
   * @return the event
   */
  @NotNull
  public static NoticeEvent completed(
      @NotNull UUID operationId,
      @NotNull String operationName,
      @Nullable OrgUnitRef owningUnit,
      @NotNull ActorRef actor,
      @NotNull BigDecimal total,
      @NotNull BigDecimal open,
      int paid,
      int count,
      int unfinished) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("operation", operationName);
    params.put("total", aUec(total));
    params.put("open", aUec(open));
    params.put("paid", Integer.toString(paid));
    params.put("count", Integer.toString(count));
    params.put("unfinished", Integer.toString(unfinished));
    NoticeEvent.NoticeEventBuilder builder =
        NoticeEvent.builder()
            .eventType(
                owningUnit == null
                    ? NotificationEventType.OPERATION_COMPLETED_UNOWNED
                    : NotificationEventType.OPERATION_COMPLETED)
            .actorSub(actor.id())
            .entityType(ENTITY_TYPE)
            .entityId(operationId)
            .renderParams(params)
            .resolvesNotificationTypes(Set.of(NotificationType.OPERATION_COMPLETED));
    if (owningUnit != null) {
      builder.contextOrgUnits(Map.of(NotificationContextRole.RESPONSIBLE, owningUnit));
    }
    return builder.build();
  }

  /**
   * A participant's payout was marked paid out.
   *
   * @param operationId the operation
   * @param operationName its name
   * @param recipientId the participant, the single recipient
   * @param amount the payout amount
   * @param donation the donated amount
   * @param fee the transfer fee
   * @param actor who marked it
   * @param lastOpenPayout whether this was the last open payout of the operation
   * @return the event
   */
  @NotNull
  public static NoticeEvent payoutMarked(
      @NotNull UUID operationId,
      @NotNull String operationName,
      @NotNull UUID recipientId,
      @NotNull BigDecimal amount,
      @NotNull BigDecimal donation,
      @NotNull BigDecimal fee,
      @NotNull ActorRef actor,
      boolean lastOpenPayout) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("operation", operationName);
    params.put("amount", aUec(amount));
    params.put("donation", fraction(donation));
    params.put("fee", fraction(fee));
    params.put("actor", actor.name());
    return NoticeEvent.builder()
        .eventType(NotificationEventType.OPERATION_PAYOUT_MARKED)
        .actorSub(actor.id())
        .entityType(ENTITY_TYPE)
        .entityId(operationId)
        .contextRecipientUserId(recipientId)
        .renderParams(params)
        .resolvesNotificationTypes(
            lastOpenPayout ? Set.of(NotificationType.OPERATION_COMPLETED) : Set.of())
        .resolvesNotificationTypesForRecipients(Set.of(NotificationType.OPERATION_PAYOUT_PAID_OUT))
        .supersedeRecipients(Set.of(recipientId))
        .build();
  }

  /**
   * A participant's paid-out mark was taken back; their paid-out notice goes.
   *
   * @param operationId the operation
   * @param recipientId the participant
   * @return the event
   */
  @NotNull
  public static NoticeEvent payoutUnmarked(@NotNull UUID operationId, @NotNull UUID recipientId) {
    return NoticeEvent.builder()
        .eventType(NotificationEventType.OPERATION_PAYOUT_UNMARKED)
        .entityType(ENTITY_TYPE)
        .entityId(operationId)
        .resolvesNotificationTypesForRecipients(Set.of(NotificationType.OPERATION_PAYOUT_PAID_OUT))
        .supersedeRecipients(Set.of(recipientId))
        .build();
  }
}
