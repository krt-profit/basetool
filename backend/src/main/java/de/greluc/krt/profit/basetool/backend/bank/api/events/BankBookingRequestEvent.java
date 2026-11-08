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

package de.greluc.krt.profit.basetool.backend.bank.api.events;

import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Shared supertype of the bank-booking-request notification events, holding their common {@link
 * #entityType()} tag.
 */
public interface BankBookingRequestEvent extends NotificationEvent {

  /**
   * Loose entity-type tag stored on every produced notification for deep-linking back to the
   * booking request. An interface field is implicitly {@code public static final}, so the three
   * implementing records inherit it and {@code BankBookingRequestCreatedEvent.ENTITY_TYPE} keeps
   * resolving for any existing reference.
   */
  String ENTITY_TYPE = "BANK_BOOKING_REQUEST";

  /**
   * The notification types that ask their recipient to act on an open request — raised when it is
   * created or corrected, cleared when it is decided, cancelled or corrected again (REQ-NOTIF-018).
   */
  @Unmodifiable
  Set<NotificationType> OPEN_REQUEST_NOTICES =
      Set.of(
          NotificationType.BANK_BOOKING_REQUEST_CREATED,
          NotificationType.BANK_BOOKING_REQUEST_UPDATED,
          NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED);

  /**
   * The notification types a decision on a request clears: its open notices and its
   * ready-to-confirm notice (REQ-BANK-057).
   */
  @Unmodifiable
  Set<NotificationType> DECIDED_REQUEST_NOTICES =
      Set.of(
          NotificationType.BANK_BOOKING_REQUEST_CREATED,
          NotificationType.BANK_BOOKING_REQUEST_UPDATED,
          NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED,
          NotificationType.BANK_BOOKING_REQUEST_APPROVED);

  /**
   * The approval-needed notice outranks the generic open notices, so the required approver holds
   * one notice per request (REQ-NOTIF-033).
   */
  @Unmodifiable
  Map<NotificationType, Set<NotificationType>> APPROVER_NOTICE_PRECEDENCE =
      Map.of(
          NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED,
          Set.of(
              NotificationType.BANK_BOOKING_REQUEST_CREATED,
              NotificationType.BANK_BOOKING_REQUEST_UPDATED));

  /**
   * Every booking-request event concerns the request it is tagged with.
   *
   * @return {@link #entityId()}
   */
  @Override
  default UUID contextBankRequestId() {
    return entityId();
  }

  /**
   * For the required approver the approval-needed notice replaces the generic one.
   *
   * @return {@link #APPROVER_NOTICE_PRECEDENCE}
   */
  @NotNull
  @Unmodifiable
  @Override
  default Map<NotificationType, Set<NotificationType>> outrankedNotificationTypes() {
    return APPROVER_NOTICE_PRECEDENCE;
  }

  /**
   * The loose entity-type tag shared by all bank-booking-request events.
   *
   * @return {@link #ENTITY_TYPE}
   */
  @Override
  default String entityType() {
    return ENTITY_TYPE;
  }
}
