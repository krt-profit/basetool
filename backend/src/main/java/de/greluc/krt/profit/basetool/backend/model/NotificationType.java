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
 * Machine identifier of a {@link Notification}'s kind.
 *
 * <p>Persisted by name and resolved by the frontend to the i18n key {@code notifications.type.*};
 * the column has no CHECK constraint, so a new constant needs no migration.
 */
public enum NotificationType {

  /**
   * A new job order ("Auftrag") was created. The default rule notifies the leadership and
   * logisticians of the responsible org unit plus the global admins, excluding the actor.
   */
  JOB_ORDER_CREATED,

  /**
   * The requester (Auftraggeber) edited one of their own job orders (REQ-ORDERS-023). The default
   * rule notifies the officers and leads of the processing org unit, excluding the actor; rendered
   * with {@code displayId}, {@code orgUnit} and {@code requester}.
   */
  JOB_ORDER_UPDATED_BY_REQUESTER,

  /**
   * An org-unit officer/lead raised a confirm-before-post bank booking request (REQ-BANK-026). The
   * default rule notifies the bank management and every employee granted on the target account,
   * excluding the requester.
   */
  BANK_BOOKING_REQUEST_CREATED,

  /**
   * A bank employee confirmed the requester's booking request (REQ-BANK-026). The default rule
   * notifies the requester.
   */
  BANK_BOOKING_REQUEST_CONFIRMED,

  /**
   * A bank employee rejected the requester's booking request (REQ-BANK-026). The default rule
   * notifies the requester; the reason is rendered in the text.
   */
  BANK_BOOKING_REQUEST_REJECTED,

  /**
   * A bank employee confirmed a booking request on an account the recipient is the responsible
   * holder of (REQ-BANK-034). Resolved via the {@code ACCOUNT_RESPONSIBLE} selector; unlike {@link
   * #BANK_BOOKING_REQUEST_CONFIRMED} it renders account-centric text.
   */
  BANK_BOOKING_REQUEST_RESPONSIBLE_CONFIRMED,

  /**
   * A bank employee rejected a booking request on an account the recipient is the responsible
   * holder of (REQ-BANK-034). Resolved via the {@code ACCOUNT_RESPONSIBLE} selector; unlike {@link
   * #BANK_BOOKING_REQUEST_REJECTED} it renders account-centric text.
   */
  BANK_BOOKING_REQUEST_RESPONSIBLE_REJECTED,

  /**
   * A new Discord user registered and awaits admin approval. The default rule notifies every admin;
   * rendered with the {@code username} parameter.
   */
  DISCORD_REGISTRATION_PENDING,

  /**
   * A member registered interest in a Materialbörse offer (REQ-MARKET-011). The default rule
   * notifies only the offer's owner, rendered with the {@code interessent} and {@code material}
   * parameters.
   */
  MATERIAL_EXCHANGE_INTEREST_REGISTERED,

  /**
   * A member signalled "Ich kann liefern" for a Materialbörse request (REQ-MARKET-020). The default
   * rule notifies only the request's owner, rendered with the {@code lieferant} and {@code
   * material} parameters.
   */
  MATERIAL_REQUEST_FULFILLMENT_SIGNALLED,

  /**
   * A member raised an erasure request (REQ-SEC-061). The default rule notifies every admin,
   * rendered with the {@code handle} parameter.
   */
  ACCOUNT_DELETION_REQUESTED,

  /**
   * An admin refused a member's erasure request (REQ-SEC-061). The default rule notifies the
   * requesting member; rendered without parameters, the reason is shown on the profile page.
   */
  ACCOUNT_DELETION_REQUEST_DECLINED
}
