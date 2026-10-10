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
  ACCOUNT_DELETION_REQUEST_DECLINED,

  /**
   * A new installation of an exchange client connected to the member's account (REQ-XCH-032),
   * rendered with the registry's {@code client} name only, never the client-supplied label.
   */
  EXCHANGE_INSTALLATION_CONNECTED,

  /**
   * An admin undid an exchange client's changes to the member's data (REQ-XCH-034), rendered with
   * the registry's {@code client} name and the restored {@code count} only.
   */
  EXCHANGE_BULK_UNDO_APPLIED,

  /**
   * Stock was transferred onto the member (REQ-INV-055), rendered with the {@code actor}, the lot
   * {@code count} and the {@code lots} list (amount, material, quality, target location).
   */
  INVENTORY_TRANSFERRED_TO_USER,

  /**
   * Someone else transferred stock away from the member (REQ-INV-055), rendered with the {@code
   * actor}, the {@code newOwner}, the lot {@code count} and the {@code lots} list (amount,
   * material, quality, source location).
   */
  INVENTORY_TRANSFERRED_FROM_USER,

  /**
   * A booking request the recipient was told about was corrected by its requester (REQ-BANK-056),
   * rendered with the same parameters as {@link #BANK_BOOKING_REQUEST_CREATED}.
   */
  BANK_BOOKING_REQUEST_UPDATED,

  /**
   * The recipient became a responsible holder of a bank account (REQ-BANK-034), rendered with the
   * {@code accountNo} and the number of requests {@code pending} their approval.
   */
  BANK_ACCOUNT_RESPONSIBLE_ASSIGNED,

  /**
   * A mission's meeting time or planned start was moved (REQ-MISSION-050), rendered with {@code
   * mission}, {@code old}, {@code new} and {@code actor}.
   */
  MISSION_RESCHEDULED,

  /** A mission was cancelled (REQ-MISSION-050), rendered with {@code mission} and {@code actor}. */
  MISSION_CANCELLED,

  /** A mission was deleted (REQ-MISSION-050), rendered with {@code mission} and {@code actor}. */
  MISSION_DELETED,

  /**
   * A mission starts in 24 hours or in one hour (REQ-MISSION-051), rendered with {@code mission},
   * {@code lead}, {@code when}, {@code where} and the participant's {@code role}.
   */
  MISSION_REMINDER,

  /**
   * A mission started and the recipient has not checked in (REQ-MISSION-052), rendered with {@code
   * mission} and {@code start}.
   */
  MISSION_CHECKIN_OPEN,

  /**
   * Somebody else added the recipient to a mission (REQ-MISSION-053), rendered with {@code
   * mission}, {@code start}, {@code actor} and the stamped {@code payout} choice.
   */
  MISSION_PARTICIPANT_ADDED_BY_OTHER,

  /**
   * Somebody else removed the recipient from a mission (REQ-MISSION-053), rendered with {@code
   * mission} and {@code actor}.
   */
  MISSION_PARTICIPANT_REMOVED_BY_OTHER,

  /**
   * A participant left a mission (REQ-MISSION-056), rendered with {@code mission}, {@code
   * participant} and the {@code freed} slot or role.
   */
  MISSION_PARTICIPANT_LEFT,

  /**
   * A mission has no end time although it is over (REQ-MISSION-054), rendered with {@code mission}
   * and {@code plannedEnd}.
   */
  MISSION_NEVER_ENDED,

  /**
   * The recipient became responsible for part of a mission (REQ-MISSION-055), rendered with {@code
   * mission}, {@code actor} and the {@code role}.
   */
  MISSION_RESPONSIBILITY_ASSIGNED,

  /**
   * The recipient's payout of an operation was marked paid out (REQ-OPERATION-020), rendered with
   * {@code operation}, {@code amount}, {@code donation}, {@code fee} and {@code actor}.
   */
  OPERATION_PAYOUT_PAID_OUT,

  /**
   * An operation was completed and its payouts are due (REQ-OPERATION-021), rendered with {@code
   * operation}, {@code total}, {@code open}, {@code paid}, {@code count} and {@code unfinished}.
   */
  OPERATION_COMPLETED;

  /**
   * Whether a member may mute this type (REQ-NOTIF-027). The account-deletion types serve a legal
   * deadline and {@link #EXCHANGE_INSTALLATION_CONNECTED} is the phishing signal, so none of them
   * can be muted.
   *
   * @return {@code false} for the types a member cannot mute, {@code true} for every other
   */
  public boolean isMutable() {
    return switch (this) {
      case ACCOUNT_DELETION_REQUESTED,
          ACCOUNT_DELETION_REQUEST_DECLINED,
          EXCHANGE_INSTALLATION_CONNECTED ->
          false;
      case JOB_ORDER_CREATED,
          JOB_ORDER_UPDATED_BY_REQUESTER,
          BANK_BOOKING_REQUEST_CREATED,
          BANK_BOOKING_REQUEST_CONFIRMED,
          BANK_BOOKING_REQUEST_REJECTED,
          BANK_BOOKING_REQUEST_RESPONSIBLE_CONFIRMED,
          BANK_BOOKING_REQUEST_RESPONSIBLE_REJECTED,
          DISCORD_REGISTRATION_PENDING,
          MATERIAL_EXCHANGE_INTEREST_REGISTERED,
          MATERIAL_REQUEST_FULFILLMENT_SIGNALLED,
          EXCHANGE_BULK_UNDO_APPLIED,
          INVENTORY_TRANSFERRED_TO_USER,
          INVENTORY_TRANSFERRED_FROM_USER,
          BANK_BOOKING_REQUEST_UPDATED,
          BANK_ACCOUNT_RESPONSIBLE_ASSIGNED,
          MISSION_RESCHEDULED,
          MISSION_CANCELLED,
          MISSION_DELETED,
          MISSION_REMINDER,
          MISSION_CHECKIN_OPEN,
          MISSION_PARTICIPANT_ADDED_BY_OTHER,
          MISSION_PARTICIPANT_REMOVED_BY_OTHER,
          MISSION_PARTICIPANT_LEFT,
          MISSION_NEVER_ENDED,
          MISSION_RESPONSIBILITY_ASSIGNED,
          OPERATION_PAYOUT_PAID_OUT,
          OPERATION_COMPLETED ->
          true;
    };
  }
}
