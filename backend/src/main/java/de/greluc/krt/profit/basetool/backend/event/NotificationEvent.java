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

package de.greluc.krt.profit.basetool.backend.event;

import de.greluc.krt.profit.basetool.backend.model.NotificationContextRole;
import de.greluc.krt.profit.basetool.backend.model.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
  default UUID contextRecipientUserId() {
    return null;
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
   * Notification types this event marks obsolete for its {@link #entityId()}: the creation pipeline
   * deletes every outstanding notification of one of these types tagged with this event's {@link
   * #entityType()} + {@link #entityId()} pair, across <em>all</em> recipients, when the event is
   * processed (REQ-NOTIF-018). Default: none.
   *
   * <p>Lets a lifecycle-terminating event clear the now-stale "action needed" notifications an
   * earlier event in the same lifecycle produced — e.g. deciding (confirm/reject) or withdrawing
   * (cancel) a bank booking request removes the {@code BANK_BOOKING_REQUEST_CREATED} inbox items
   * the bank staff were shown. The removal runs regardless of whether this event itself resolves
   * any recipients, so a purely-terminating event (a self-service cancel that notifies nobody)
   * still clears the stale items.
   *
   * @return the notification types superseded for this event's entity; never {@code null}, possibly
   *     empty
   */
  default Set<NotificationType> resolvesNotificationTypes() {
    return Set.of();
  }
}
