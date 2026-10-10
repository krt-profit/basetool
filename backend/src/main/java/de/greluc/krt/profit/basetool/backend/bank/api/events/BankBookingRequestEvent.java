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

import de.greluc.krt.profit.basetool.backend.model.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import java.util.Set;
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
          NotificationType.BANK_BOOKING_REQUEST_UPDATED);

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
