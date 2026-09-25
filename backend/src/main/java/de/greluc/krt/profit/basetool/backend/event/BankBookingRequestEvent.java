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
   * The loose entity-type tag shared by all bank-booking-request events.
   *
   * @return {@link #ENTITY_TYPE}
   */
  @Override
  default String entityType() {
    return ENTITY_TYPE;
  }
}
