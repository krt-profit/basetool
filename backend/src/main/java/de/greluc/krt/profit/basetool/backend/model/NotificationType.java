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
   * A mission's meeting time or planned start was moved (REQ-MISSION-021), rendered with {@code
   * mission}, {@code old}, {@code new} and {@code actor}.
   */
  MISSION_RESCHEDULED,

  /** A mission was cancelled (REQ-MISSION-021), rendered with {@code mission} and {@code actor}. */
  MISSION_CANCELLED,

  /** A mission was deleted (REQ-MISSION-021), rendered with {@code mission} and {@code actor}. */
  MISSION_DELETED,

  /**
   * A mission starts in 24 hours or in one hour (REQ-MISSION-022), rendered with {@code mission},
   * {@code lead}, {@code when}, {@code where} and the participant's {@code role}.
   */
  MISSION_REMINDER,

  /**
   * A mission started and the recipient has not checked in (REQ-MISSION-023), rendered with {@code
   * mission} and {@code start}.
   */
  MISSION_CHECKIN_OPEN,

  /**
   * Somebody else added the recipient to a mission (REQ-MISSION-024), rendered with {@code
   * mission}, {@code start}, {@code actor} and the stamped {@code payout} choice.
   */
  MISSION_PARTICIPANT_ADDED_BY_OTHER,

  /**
   * Somebody else removed the recipient from a mission (REQ-MISSION-024), rendered with {@code
   * mission} and {@code actor}.
   */
  MISSION_PARTICIPANT_REMOVED_BY_OTHER,

  /**
   * A participant left a mission (REQ-MISSION-027), rendered with {@code mission}, {@code
   * participant} and the {@code freed} slot or role.
   */
  MISSION_PARTICIPANT_LEFT,

  /**
   * A mission has no end time although it is over (REQ-MISSION-025), rendered with {@code mission}
   * and {@code plannedEnd}.
   */
  MISSION_NEVER_ENDED,

  /**
   * The recipient became responsible for part of a mission (REQ-MISSION-026), rendered with {@code
   * mission}, {@code actor} and the {@code role}.
   */
  MISSION_RESPONSIBILITY_ASSIGNED,

  /**
   * The recipient's payout of an operation was marked paid out (REQ-MISSION-028), rendered with
   * {@code operation}, {@code amount}, {@code donation}, {@code fee} and {@code actor}.
   */
  OPERATION_PAYOUT_PAID_OUT,

  /**
   * An operation was completed and its payouts are due (REQ-MISSION-029), rendered with {@code
   * operation}, {@code total}, {@code open}, {@code paid}, {@code count} and {@code unfinished}.
   */
  OPERATION_COMPLETED,

  /**
   * A job order moved to the recipient's unit (REQ-ORDERS-041), rendered with {@code displayId},
   * {@code handle}, {@code from} and {@code to}.
   */
  JOB_ORDER_REASSIGNED,

  /**
   * A job order of the recipient's unit was completed, rejected or deleted (REQ-ORDERS-042),
   * rendered with {@code displayId}, {@code handle}, {@code unit} and the {@code status} word.
   */
  JOB_ORDER_FINISHED,

  /**
   * The recipient was assigned to a job order (REQ-ORDERS-043), rendered with {@code displayId},
   * {@code handle} and {@code actor}.
   */
  JOB_ORDER_ASSIGNED,

  /**
   * The recipient's material claim on a job order was withdrawn (REQ-ORDERS-044), rendered with
   * {@code displayId}, {@code handle}, {@code material} and the {@code reason} word.
   */
  JOB_ORDER_CLAIM_WITHDRAWN,

  /**
   * A refinery order is ready to collect (REQ-REFINERY-023), rendered with {@code order}, {@code
   * location} and the {@code outputs}.
   */
  REFINERY_ORDER_READY,

  /**
   * Somebody else changed, cancelled or stored the recipient's refinery order, or booked its yield
   * onto the recipient (REQ-REFINERY-024), rendered with {@code order}, {@code location}, {@code
   * actor} and the {@code change} word.
   */
  REFINERY_ORDER_CHANGED_BY_OTHER,

  /**
   * An offer the recipient was interested in is gone (REQ-MARKET-021), rendered with {@code item}
   * and the {@code reason} word.
   */
  MATERIAL_EXCHANGE_OFFER_UNAVAILABLE,

  /** A request the recipient could supply is gone (REQ-MARKET-022), rendered with {@code item}. */
  MATERIAL_REQUEST_UNAVAILABLE,

  /**
   * Somebody else discarded or sold part of the recipient's Lager stock (REQ-INV-056), rendered
   * with {@code actor}, the {@code action} word, {@code count} and the {@code lots}.
   */
  INVENTORY_BOOKED_OUT_BY_OTHER,

  /**
   * A booking request is approved and ready to confirm (REQ-BANK-057), rendered with {@code type},
   * {@code amount}, {@code accountNo} and {@code approver}.
   */
  BANK_BOOKING_REQUEST_APPROVED,

  /**
   * The recipient's bank access to an account was granted or changed (REQ-BANK-058), rendered with
   * {@code accountNo}, the {@code grantChange} word and the three rights words.
   */
  BANK_GRANT_CHANGED,

  /**
   * The recipient's bank access to an account was withdrawn (REQ-BANK-058), rendered with {@code
   * accountNo} and {@code actor}.
   */
  BANK_GRANT_REVOKED,

  /**
   * Bank staff paid an amount out to the recipient (REQ-BANK-059), rendered with {@code amount},
   * {@code fee}, {@code accountNo} and {@code actor}.
   */
  BANK_PAYOUT_RECEIVED,

  /**
   * Bank staff moved an amount to the recipient's holder, who must take the aUEC over
   * (REQ-BANK-059), rendered with {@code amount} and {@code actor}.
   */
  BANK_HOLDER_TRANSFER_RECEIVED,

  /**
   * Bank staff debited the recipient's account or reversed a booking on it (REQ-BANK-059), rendered
   * with {@code accountNo}, {@code amount}, {@code fee}, the {@code debit} word and {@code actor}.
   */
  BANK_ACCOUNT_DEBITED,

  /**
   * A bank holder was deactivated but still holds aUEC (REQ-BANK-060), rendered with {@code holder}
   * and {@code balance}.
   */
  BANK_HOLDER_DEACTIVATED_WITH_BALANCE,

  /**
   * A member's OFFICER role does not fit their leadership seats (REQ-ORG-029), rendered with {@code
   * member}, {@code unit}, the {@code seat} and {@code rank} words and the {@code mismatch} word.
   */
  ORG_LEADERSHIP_ROLE_MISMATCH,

  /**
   * A member of the recipient's unit left the organisation (REQ-ORG-030), rendered with {@code
   * member}, {@code unit}, the {@code reason} word and the {@code vacancy} word.
   */
  ORG_MEMBER_DEPARTED,

  /**
   * The recipient's ship was assigned to a mission unit (REQ-HANGAR-005), rendered with {@code
   * shipType}, {@code mission}, {@code unit} and {@code start}.
   */
  HANGAR_SHIP_ASSIGNED,

  /**
   * A deleted ship falls out of a planned mission (REQ-HANGAR-006), rendered with {@code shipType},
   * {@code mission} and {@code unit}.
   */
  HANGAR_SHIP_REMOVED_FROM_UNIT,

  /**
   * The fitted marks of the recipient's ships were reset (REQ-HANGAR-007), rendered with {@code
   * count}.
   */
  HANGAR_FITTED_RESET,

  /**
   * An admin changed the recipient's hangar (REQ-HANGAR-008), rendered with {@code actor}, the
   * {@code change} word and {@code shipType}.
   */
  HANGAR_CHANGED_BY_ADMIN,

  /**
   * An admin changed the recipient's blueprints (REQ-HANGAR-008), rendered with {@code actor}, the
   * {@code change} word and {@code subject}.
   */
  BLUEPRINT_CHANGED_BY_ADMIN,

  /**
   * An admin cleared the recipient's removable blueprints (REQ-HANGAR-008), rendered with {@code
   * actor} and {@code count}.
   */
  BLUEPRINT_PURGED_BY_ADMIN;

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
          OPERATION_COMPLETED,
          JOB_ORDER_REASSIGNED,
          JOB_ORDER_FINISHED,
          JOB_ORDER_ASSIGNED,
          JOB_ORDER_CLAIM_WITHDRAWN,
          REFINERY_ORDER_READY,
          REFINERY_ORDER_CHANGED_BY_OTHER,
          MATERIAL_EXCHANGE_OFFER_UNAVAILABLE,
          MATERIAL_REQUEST_UNAVAILABLE,
          INVENTORY_BOOKED_OUT_BY_OTHER,
          BANK_BOOKING_REQUEST_APPROVED,
          BANK_GRANT_CHANGED,
          BANK_GRANT_REVOKED,
          BANK_PAYOUT_RECEIVED,
          BANK_HOLDER_TRANSFER_RECEIVED,
          BANK_ACCOUNT_DEBITED,
          BANK_HOLDER_DEACTIVATED_WITH_BALANCE,
          ORG_LEADERSHIP_ROLE_MISMATCH,
          ORG_MEMBER_DEPARTED,
          HANGAR_SHIP_ASSIGNED,
          HANGAR_SHIP_REMOVED_FROM_UNIT,
          HANGAR_FITTED_RESET,
          HANGAR_CHANGED_BY_ADMIN,
          BLUEPRINT_CHANGED_BY_ADMIN,
          BLUEPRINT_PURGED_BY_ADMIN ->
          true;
    };
  }
}
