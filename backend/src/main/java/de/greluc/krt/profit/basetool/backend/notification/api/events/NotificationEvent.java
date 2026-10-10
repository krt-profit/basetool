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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Contract every notification-producing domain event implements.
 *
 * <p>Published via {@code ApplicationEventPublisher} inside the originating {@code @Transactional}
 * method and consumed after commit by the notification listener. Implementations carry only
 * immutable scalars (ids, kinds, render parameters) — never managed entities — so the listener can
 * run safely on another thread in a fresh transaction. A new producer adds one implementation; the
 * rule engine and creation pipeline need no changes.
 */
public interface NotificationEvent {

  /**
   * The trigger type, matched against {@code notification_rule.event_type}.
   *
   * @return the event type
   */
  NotificationEventType eventType();

  /**
   * The acting user's {@code sub}, excluded from recipients when a matching rule sets {@code
   * excludeActor}. {@code null} for anonymous/guest actors.
   *
   * @return the actor sub, or {@code null}
   */
  UUID actorSub();

  /**
   * The org units this event exposes by role, for {@code ORG_RELATIVE_ROLE} selector resolution.
   *
   * @return the context org units keyed by role; never {@code null}
   */
  Map<NotificationContextRole, OrgUnitRef> contextOrgUnits();

  /**
   * The bank account this event concerns, for {@code ACCOUNT_GRANT} selector resolution (the
   * employees holding a {@code bank_account_grant} on it). {@code null} for events that concern no
   * account — the default, so non-bank producers need not implement it.
   *
   * @return the context bank account id, or {@code null}
   */
  @Nullable
  default UUID contextAccountId() {
    return null;
  }

  /**
   * The single user this event is directed at, for {@code EVENT_RECIPIENT} selector resolution —
   * for example the officer/lead who raised a booking request, notified when it is decided.
   * Distinct from {@link #actorSub()} (who caused the event). {@code null} for events with no
   * directed recipient — the default.
   *
   * @return the directed recipient's sub, or {@code null}
   */
  @Nullable
  default UUID contextRecipientUserId() {
    return null;
  }

  /**
   * The set of affected users this event carries, for {@code EVENT_RECIPIENTS} selector resolution
   * — bulk actions that touch many members, such as the owners of reset ships or the interested
   * members of a withdrawn offer. Distinct from {@link #contextRecipientUserId()}, which names one.
   *
   * @return the directed recipients' subs; never {@code null}, empty by default
   */
  @NotNull
  @Unmodifiable
  default Set<UUID> contextRecipientUserIds() {
    return Set.of();
  }

  /**
   * The mission this event concerns, for {@code MISSION_PARTICIPANTS} and {@code
   * MISSION_LEADERSHIP} selector resolution. {@code null} for events that concern no mission — the
   * default.
   *
   * @return the context mission id, or {@code null}
   */
  @Nullable
  default UUID contextMissionId() {
    return null;
  }

  /**
   * Whether {@code MISSION_PARTICIPANTS} narrows to the participants who have not checked in yet.
   *
   * @return {@code true} to reach only participants without a check-in; {@code false} by default
   */
  default boolean contextMissionOnlyNotCheckedIn() {
    return false;
  }

  /**
   * The registry client this event concerns, for {@code EXCHANGE_CLIENT_HOLDERS} selector
   * resolution. {@code null} for events that concern no single client — the default.
   *
   * @return the context exchange client id, or {@code null}
   */
  @Nullable
  default UUID contextExchangeClientId() {
    return null;
  }

  /**
   * Whether {@code EXCHANGE_CLIENT_HOLDERS} reaches the holders of every client rather than one,
   * for an event that concerns the exchange as a whole. Ignored when {@link
   * #contextExchangeClientId()} is set.
   *
   * @return {@code true} to reach the holders of any client; {@code false} by default
   */
  default boolean contextAllExchangeClients() {
    return false;
  }

  /**
   * Loose type tag of the originating aggregate stored on each notification for deep-linking.
   *
   * @return the entity type tag (e.g. {@code JOB_ORDER})
   */
  String entityType();

  /**
   * Id of the originating aggregate stored on each notification for deep-linking.
   *
   * @return the entity id
   */
  UUID entityId();

  /**
   * Render parameters stored on each created notification so the frontend localizes the text.
   *
   * @return the i18n render parameters; never {@code null}, possibly empty
   */
  Map<String, String> renderParams();

  /**
   * Notification types this event marks obsolete for its {@link #entityId()} (REQ-NOTIF-018).
   *
   * <p>When the event is processed, every outstanding notification of these types tagged with this
   * event's {@link #entityType()} and {@link #entityId()} is deleted for all recipients, even if
   * the event itself resolves no recipients. Default: none.
   *
   * @return the superseded notification types; never {@code null}, possibly empty
   */
  @NotNull
  @Unmodifiable
  default Set<NotificationType> resolvesNotificationTypes() {
    return Set.of();
  }

  /**
   * The members whose notice about this event's entity is decided afresh, for an event that
   * reconciles an existing notice instead of raising a new one (REQ-NOTIF-023).
   *
   * <p>When non-empty, the event touches only these members: each one the rules resolve now and who
   * holds no notification of {@link #resolvesNotificationTypes()} for the entity gets one; each one
   * the rules no longer resolve loses theirs. Nobody else is notified or cleared. Default: none,
   * which processes the event normally.
   *
   * @return the members to reconcile; never {@code null}, empty for an ordinary event
   */
  @NotNull
  @Unmodifiable
  default Set<UUID> reconcileRecipients() {
    return Set.of();
  }

  /**
   * Notification types this event marks obsolete for {@link #supersedeRecipients()} only
   * (REQ-NOTIF-025).
   *
   * <p>Where {@link #resolvesNotificationTypes()} clears a notice for everybody, this clears it for
   * the named members and leaves everybody else's untouched — for example the open check-in notice
   * of the one participant who checked in. Ignored while {@link #supersedeRecipients()} is empty.
   * Default: none.
   *
   * @return the types superseded for the named members; never {@code null}, possibly empty
   */
  @NotNull
  @Unmodifiable
  default Set<NotificationType> resolvesNotificationTypesForRecipients() {
    return Set.of();
  }

  /**
   * The members whose notices of {@link #resolvesNotificationTypesForRecipients()} about this
   * event's entity are removed (REQ-NOTIF-025). Default: none.
   *
   * @return the members whose notices are superseded; never {@code null}, possibly empty
   */
  @NotNull
  @Unmodifiable
  default Set<UUID> supersedeRecipients() {
    return Set.of();
  }
}
