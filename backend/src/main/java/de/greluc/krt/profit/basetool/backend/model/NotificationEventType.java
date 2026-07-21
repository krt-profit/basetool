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
 * Identifies the domain trigger that a {@link NotificationRule} reacts to.
 *
 * <p>Distinct from {@link NotificationType}: an event type names <em>what happened</em> (the
 * trigger a rule matches on) while a notification type names <em>what is shown</em> (the rendered
 * message a rule produces). They coincide for the first use case but are kept separate so a single
 * trigger can drive differently-rendered notifications in future. The constant is persisted via
 * {@code @Enumerated(STRING)} and matched against {@code notification_rule.event_type}.
 */
public enum NotificationEventType {

  /** A new job order ("Auftrag") was created. */
  JOB_ORDER_CREATED,

  /**
   * The requesting owner (Auftraggeber) edited one of their own job orders — changed quantities,
   * added/removed not-yet-delivered items or materials, or edited the comment (REQ-ORDERS-023). The
   * default rule notifies the officers and leads of the processing (responsible) org unit; the
   * editing actor is excluded.
   */
  JOB_ORDER_UPDATED_BY_REQUESTER,

  /**
   * An org-unit officer/lead raised a confirm-before-post bank deposit/withdrawal request (epic
   * #666 F2, REQ-BANK-026). The default rule notifies the bank management and the employees granted
   * on the target account.
   */
  BANK_BOOKING_REQUEST_CREATED,

  /**
   * A bank employee confirmed a booking request (epic #666 F2, REQ-BANK-026). The default rule
   * notifies the requesting officer/lead.
   */
  BANK_BOOKING_REQUEST_CONFIRMED,

  /**
   * A bank employee rejected a booking request (epic #666 F2, REQ-BANK-026). The default rule
   * notifies the requesting officer/lead.
   */
  BANK_BOOKING_REQUEST_REJECTED,

  /**
   * A requester withdrew (cancelled) their own still-pending bank booking request (REQ-BANK-022,
   * REQ-NOTIF-018). No default rule notifies anyone — the requester is the actor; the event exists
   * solely so the pipeline clears the now-stale {@code BANK_BOOKING_REQUEST_CREATED} items the bank
   * staff were shown.
   */
  BANK_BOOKING_REQUEST_CANCELLED,

  /**
   * A new Discord user registered and is awaiting admin approval (epic #720, Track 1,
   * REQ-NOTIF-012). The default rule notifies every admin.
   */
  DISCORD_REGISTRATION_PENDING,

  /**
   * A member registered interest in a Materialbörse offer (#1187, REQ-MARKET-011). The default rule
   * notifies the offer's owner (the Anbieter) via the {@code EVENT_RECIPIENT} selector.
   */
  MATERIAL_EXCHANGE_INTEREST_REGISTERED,

  /**
   * A member signalled they can supply a Materialbörse request (Gesuch) — "Ich kann liefern"
   * (REQ-MARKET-020). The default rule notifies the request's owner (the Suchende) via the {@code
   * EVENT_RECIPIENT} selector.
   */
  MATERIAL_REQUEST_FULFILLMENT_SIGNALLED
}
