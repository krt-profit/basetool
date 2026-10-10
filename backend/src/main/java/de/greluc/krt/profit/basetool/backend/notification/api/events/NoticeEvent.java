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

package de.greluc.krt.profit.basetool.backend.notification.api.events;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A plain {@link NotificationEvent} built from its parts, for the producers whose event needs no
 * behaviour of its own. A module's factory class names the event and fills the parts (for example
 * {@code MissionNotices}); the rule engine sees an ordinary event.
 *
 * <p>Every collection is copied on construction and a {@code null} collection means empty, so the
 * event stays immutable and the builder can leave out what an event does not carry.
 *
 * @param eventType the trigger type
 * @param actorSub the acting member, or {@code null}
 * @param entityType the loose entity-type tag stored on the notification
 * @param entityId the loose entity id stored on the notification
 * @param contextOrgUnits the org units the event exposes by role
 * @param contextAccountId the bank account the event concerns
 * @param contextRecipientUserId the single member the event is directed at
 * @param contextRecipientUserIds the members the event lists
 * @param contextMissionId the mission the event concerns
 * @param contextMissionOnlyNotCheckedIn whether the mission's participants narrow to the not
 *     checked in
 * @param contextExchangeClientId the registry client the event concerns
 * @param contextAllExchangeClients whether the event concerns every client's holders
 * @param renderParams the render parameters stored on the notification
 * @param resolvesNotificationTypes the types superseded for everyone
 * @param resolvesNotificationTypesForRecipients the types superseded for {@code
 *     supersedeRecipients}
 * @param supersedeRecipients the members whose notices are superseded
 * @param reconcileRecipients the members whose notice is reconciled
 */
@Builder
public record NoticeEvent(
    @NotNull NotificationEventType eventType,
    @Nullable UUID actorSub,
    @NotNull String entityType,
    @NotNull UUID entityId,
    @Nullable Map<NotificationContextRole, OrgUnitRef> contextOrgUnits,
    @Nullable UUID contextAccountId,
    @Nullable UUID contextRecipientUserId,
    @Nullable Set<UUID> contextRecipientUserIds,
    @Nullable UUID contextMissionId,
    boolean contextMissionOnlyNotCheckedIn,
    @Nullable UUID contextExchangeClientId,
    boolean contextAllExchangeClients,
    @Nullable Map<String, String> renderParams,
    @Nullable Set<NotificationType> resolvesNotificationTypes,
    @Nullable Set<NotificationType> resolvesNotificationTypesForRecipients,
    @Nullable Set<UUID> supersedeRecipients,
    @Nullable Set<UUID> reconcileRecipients)
    implements NotificationEvent {

  /**
   * Copies every collection and turns an absent one into an empty one.
   *
   * @param eventType the trigger type
   * @param actorSub the acting member, or {@code null}
   * @param entityType the loose entity-type tag
   * @param entityId the loose entity id
   * @param contextOrgUnits the org units by role
   * @param contextAccountId the bank account
   * @param contextRecipientUserId the single directed member
   * @param contextRecipientUserIds the listed members
   * @param contextMissionId the mission
   * @param contextMissionOnlyNotCheckedIn whether participants narrow to the not checked in
   * @param contextExchangeClientId the registry client
   * @param contextAllExchangeClients whether every client's holders are meant
   * @param renderParams the render parameters
   * @param resolvesNotificationTypes the types superseded for everyone
   * @param resolvesNotificationTypesForRecipients the types superseded for named members
   * @param supersedeRecipients the members whose notices are superseded
   * @param reconcileRecipients the members whose notice is reconciled
   */
  public NoticeEvent {
    contextOrgUnits = contextOrgUnits == null ? Map.of() : Map.copyOf(contextOrgUnits);
    contextRecipientUserIds =
        contextRecipientUserIds == null ? Set.of() : Set.copyOf(contextRecipientUserIds);
    renderParams = renderParams == null ? Map.of() : withoutNulls(renderParams);
    resolvesNotificationTypes =
        resolvesNotificationTypes == null ? Set.of() : Set.copyOf(resolvesNotificationTypes);
    resolvesNotificationTypesForRecipients =
        resolvesNotificationTypesForRecipients == null
            ? Set.of()
            : Set.copyOf(resolvesNotificationTypesForRecipients);
    supersedeRecipients = supersedeRecipients == null ? Set.of() : Set.copyOf(supersedeRecipients);
    reconcileRecipients = reconcileRecipients == null ? Set.of() : Set.copyOf(reconcileRecipients);
  }

  @Override
  public @NotNull Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return contextOrgUnits;
  }

  @Override
  public @NotNull @Unmodifiable Set<UUID> contextRecipientUserIds() {
    return contextRecipientUserIds;
  }

  @Override
  public @NotNull Map<String, String> renderParams() {
    return renderParams;
  }

  @Override
  public @NotNull @Unmodifiable Set<NotificationType> resolvesNotificationTypes() {
    return resolvesNotificationTypes;
  }

  @Override
  public @NotNull @Unmodifiable Set<NotificationType> resolvesNotificationTypesForRecipients() {
    return resolvesNotificationTypesForRecipients;
  }

  @Override
  public @NotNull @Unmodifiable Set<UUID> supersedeRecipients() {
    return supersedeRecipients;
  }

  @Override
  public @NotNull @Unmodifiable Set<UUID> reconcileRecipients() {
    return reconcileRecipients;
  }

  private static Map<String, String> withoutNulls(Map<String, String> params) {
    Map<String, String> copy = new java.util.LinkedHashMap<>();
    params.forEach((key, value) -> copy.put(key, value == null ? "" : value));
    return Map.copyOf(copy);
  }
}
