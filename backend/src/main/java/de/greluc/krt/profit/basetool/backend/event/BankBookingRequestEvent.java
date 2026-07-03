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
 * Shared supertype for the three bank-booking-request notification events (created / confirmed /
 * rejected). They all deep-link to the same aggregate kind, so the loose entity-type tag and its
 * {@link #entityType()} accessor live here once instead of being repeated as an identical {@code
 * public static final String ENTITY_TYPE} field plus {@code entityType()} override on each event
 * record (#906 Q7).
 *
 * <p>The constant stays on this bank-specific interface rather than on {@link NotificationEvent}
 * because that broader contract is also implemented by events with different tags (e.g. {@code
 * JOB_ORDER}, {@code DISCORD_REGISTRATION}); a single inherited value there would be wrong.
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
