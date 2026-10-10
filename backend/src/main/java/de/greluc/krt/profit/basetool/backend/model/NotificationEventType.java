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

package de.greluc.krt.profit.basetool.backend.model;

/**
 * The domain trigger a {@link NotificationRule} reacts to, as opposed to the rendered {@link
 * NotificationType} the rule produces.
 *
 * <p>Persisted by name and matched against {@code notification_rule.event_type}.
 */
public enum NotificationEventType {

  /** A new job order ("Auftrag") was created. */
  JOB_ORDER_CREATED,

  /**
   * The requester (Auftraggeber) edited one of their own job orders (REQ-ORDERS-023). The default
   * rule notifies the officers and leads of the processing org unit, excluding the actor.
   */
  JOB_ORDER_UPDATED_BY_REQUESTER,

  /**
   * An org-unit officer/lead raised a confirm-before-post bank booking request (REQ-BANK-026). The
   * default rule notifies the bank management and the employees granted on the target account.
   */
  BANK_BOOKING_REQUEST_CREATED,

  /**
   * A bank employee confirmed a booking request (REQ-BANK-026). The default rule notifies the
   * requester.
   */
  BANK_BOOKING_REQUEST_CONFIRMED,

  /**
   * A bank employee rejected a booking request (REQ-BANK-026). The default rule notifies the
   * requester.
   */
  BANK_BOOKING_REQUEST_REJECTED,

  /**
   * A requester cancelled their own pending bank booking request (REQ-BANK-022). Notifies nobody;
   * it clears the stale {@code BANK_BOOKING_REQUEST_CREATED} items shown to bank staff.
   */
  BANK_BOOKING_REQUEST_CANCELLED,

  /**
   * A new Discord user registered and awaits admin approval (REQ-NOTIF-012). The default rule
   * notifies every admin.
   */
  DISCORD_REGISTRATION_PENDING,

  /**
   * A member registered interest in a Materialbörse offer (REQ-MARKET-011). The default rule
   * notifies the offer's owner via the {@code EVENT_RECIPIENT} selector.
   */
  MATERIAL_EXCHANGE_INTEREST_REGISTERED,

  /**
   * A member signalled they can supply a Materialbörse request (Gesuch) — "Ich kann liefern"
   * (REQ-MARKET-020). The default rule notifies the request's owner (the Suchende) via the {@code
   * EVENT_RECIPIENT} selector.
   */
  MATERIAL_REQUEST_FULFILLMENT_SIGNALLED,

  /**
   * A member raised an Art. 17 erasure request (REQ-SEC-061). The default rule notifies every
   * admin, because the request carries a legal deadline — Art. 12(3) gives the controller one month
   * to respond — and a queue nobody is told about is how that month passes.
   */
  ACCOUNT_DELETION_REQUESTED,

  /**
   * An admin refused a member's erasure request (REQ-SEC-061). The default rule notifies the
   * requesting member via the {@code EVENT_RECIPIENT} selector, because Art. 12(4) obliges the
   * controller to tell them. The reasoning does not ride the event — it is shown on the member's
   * profile page, where the request is.
   */
  ACCOUNT_DELETION_REQUEST_DECLINED,

  /**
   * A member's erasure request was withdrawn or executed (REQ-SEC-061).
   *
   * <p>Creates no notification; it clears the administrators' {@code ACCOUNT_DELETION_REQUESTED}
   * items on every terminal path.
   */
  ACCOUNT_DELETION_REQUEST_RESOLVED,

  /**
   * A registered exchange client was seen with a new installation of a member for the first time
   * (REQ-XCH-032). The default rule notifies that member via the {@code EVENT_RECIPIENT} selector.
   */
  EXCHANGE_INSTALLATION_CONNECTED,

  /**
   * An admin's bulk undo restored entries of a member that an exchange client had written
   * (REQ-XCH-034). The default rule notifies that member via the {@code EVENT_RECIPIENT} selector.
   */
  EXCHANGE_BULK_UNDO_APPLIED,

  /**
   * A Lager transfer booked stock onto another member (REQ-INV-055). Published once per action and
   * new owner; the default rule notifies that member via the {@code EVENT_RECIPIENT} selector.
   */
  INVENTORY_TRANSFERRED_TO_USER,

  /**
   * A Lager transfer made by someone else moved stock away from its member (REQ-INV-055). Published
   * once per action and previous owner; the default rule notifies that member via the {@code
   * EVENT_RECIPIENT} selector.
   */
  INVENTORY_TRANSFERRED_FROM_USER,

  /**
   * A pending registration was approved, rejected or deleted (REQ-NOTIF-012). Creates no
   * notification; it clears every admin's {@code DISCORD_REGISTRATION_PENDING} item for it.
   */
  DISCORD_REGISTRATION_DECIDED,

  /**
   * A job order was completed, rejected or deleted (REQ-NOTIF-008). Creates no notification; it
   * clears the open {@code JOB_ORDER_CREATED} and {@code JOB_ORDER_UPDATED_BY_REQUESTER} items.
   */
  JOB_ORDER_CLOSED,

  /**
   * A requester corrected their own pending booking request (REQ-BANK-056). The default rule
   * notifies the recipients of {@link #BANK_BOOKING_REQUEST_CREATED} anew; the event clears their
   * outdated items first.
   */
  BANK_BOOKING_REQUEST_UPDATED_BY_REQUESTER,

  /**
   * A member became a responsible holder of a bank account (REQ-BANK-034). Published once per new
   * holder; the default rule notifies them via the {@code EVENT_RECIPIENT} selector.
   */
  BANK_ACCOUNT_RESPONSIBLE_ASSIGNED
}
