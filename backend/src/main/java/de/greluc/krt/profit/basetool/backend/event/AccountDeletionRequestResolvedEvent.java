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
 * Published when a member's Art. 17 erasure request reaches a terminal state without producing a
 * notification of its own — a withdrawal, or the execution itself (REQ-SEC-061, REQ-NOTIF-018).
 *
 * <p><b>Directed at nobody.</b> Like {@link BankBookingRequestCancelledEvent}, its only effect on
 * the notification pipeline is to clear the now-stale {@code ACCOUNT_DELETION_REQUESTED} items the
 * administrators were shown. It seeds no rule, creates no notification, needs no message template.
 *
 * <p><b>Why it exists.</b> The request notification carries the member's handle in its render
 * parameters, one row per administrator, and none of the three terminal transitions superseded it:
 * the declined event had no {@code resolvesNotificationTypes()} override, and withdrawal and
 * execution published nothing at all. So every administrator's bell kept showing "X beantragt die
 * Löschung des eigenen Kontos" after the request was withdrawn, refused or carried out — and on
 * execution the departed member's name sat in {@code notification.params} until the 180-day unread
 * sweep reaped it, because {@code UserDeletionService} deletes notifications by <em>recipient</em>
 * and the recipients are other people.
 *
 * <p>Superseding is a better answer than rewriting the payload, which is what the erasure used to
 * do: the row is gone when the request is decided, on <b>every</b> path rather than only when the
 * member ticked the history checkbox and an admin granted it.
 *
 * <p>Carries only the member's id, so the after-commit listener never touches an entity the
 * execution has already deleted.
 *
 * @param userId the member whose request was resolved; also the loose entity id the {@code
 *     ACCOUNT_DELETION_REQUESTED} notifications were tagged with
 */
public record AccountDeletionRequestResolvedEvent(UUID userId) implements NotificationEvent {

  /** The loose entity type both deletion-request notifications are tagged with. */
  public static final String ENTITY_TYPE = AccountDeletionRequestedEvent.ENTITY_TYPE;

  @Override
  public NotificationEventType eventType() {
    return NotificationEventType.ACCOUNT_DELETION_REQUEST_RESOLVED;
  }

  /**
   * The member themselves, whether they withdrew the request or an admin carried it out.
   *
   * <p>On the execution path the account is already gone by the time the listener runs, which is
   * harmless: the actor reference is a loose {@code sub} with no foreign key, and nothing here
   * resolves a recipient from it.
   *
   * @return the member's id
   */
  @Override
  public UUID actorSub() {
    return userId;
  }

  @Override
  public Map<NotificationContextRole, OrgUnitRef> contextOrgUnits() {
    return Map.of();
  }

  @Override
  public String entityType() {
    return ENTITY_TYPE;
  }

  @Override
  public UUID entityId() {
    return userId;
  }

  @Override
  public Map<String, String> renderParams() {
    return Map.of();
  }

  /**
   * The request is settled, so the administrators' "member requests erasure" items are stale.
   *
   * @return the singleton {@link NotificationType#ACCOUNT_DELETION_REQUESTED}
   */
  @Override
  public Set<NotificationType> resolvesNotificationTypes() {
    return Set.of(NotificationType.ACCOUNT_DELETION_REQUESTED);
  }
}
