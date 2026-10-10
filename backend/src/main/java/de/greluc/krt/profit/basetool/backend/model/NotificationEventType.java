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
  BANK_ACCOUNT_RESPONSIBLE_ASSIGNED,

  /**
   * A mission's meeting time or planned start changed (REQ-MISSION-021). The default rule notifies
   * the participants and clears the earlier reschedule and reminder notices.
   */
  MISSION_RESCHEDULED,

  /**
   * A mission was cancelled (REQ-MISSION-021). The default rule notifies the participants and
   * clears the mission's reschedule, reminder and check-in notices.
   */
  MISSION_CANCELLED,

  /**
   * A mission was deleted (REQ-MISSION-021). Carries the participants as event-listed recipients
   * because the mission is gone; clears the mission's other notices.
   */
  MISSION_DELETED,

  /**
   * A mission starts in 24 hours or in one hour (REQ-MISSION-022). Published once per participant
   * by the time-based producer; the default rule notifies that participant.
   */
  MISSION_REMINDER_DUE,

  /**
   * A mission became active (REQ-MISSION-023). The default rule notifies the participants who have
   * not checked in.
   */
  MISSION_STARTED,

  /**
   * A participant checked in (REQ-MISSION-023). Creates no notification; clears that participant's
   * open check-in notice.
   */
  MISSION_CHECKED_IN,

  /**
   * Somebody else added a member to a mission (REQ-MISSION-024). The default rule notifies that
   * member.
   */
  MISSION_PARTICIPANT_ADDED,

  /**
   * Somebody else removed a member from a mission (REQ-MISSION-024). The default rule notifies that
   * member; the event clears their add, reminder and check-in notices.
   */
  MISSION_PARTICIPANT_REMOVED,

  /**
   * A participant left a mission they held a slot in or that starts soon (REQ-MISSION-027). The
   * default rule notifies the mission's leadership.
   */
  MISSION_PARTICIPANT_LEFT,

  /**
   * A mission was completed without an end time, or ran past its planned end without one
   * (REQ-MISSION-025). The default rule notifies the mission's leadership.
   */
  MISSION_NEVER_ENDED,

  /**
   * A mission got its actual end time (REQ-MISSION-025). Creates no notification; clears the
   * mission's never-ended and check-in notices.
   */
  MISSION_END_RECORDED,

  /**
   * A member became the owner, a co-manager, the party lead or the responsible of a unit of a
   * mission (REQ-MISSION-026). The default rule notifies that member.
   */
  MISSION_RESPONSIBILITY_ASSIGNED,

  /**
   * A participant's payout of an operation was marked paid out (REQ-MISSION-028). The default rule
   * notifies that participant; when it was the last open payout the event clears the managers'
   * completion notice.
   */
  OPERATION_PAYOUT_MARKED,

  /**
   * A participant's paid-out mark was taken back (REQ-MISSION-028). Creates no notification; clears
   * that participant's paid-out notice.
   */
  OPERATION_PAYOUT_UNMARKED,

  /**
   * An operation of an organisational unit was completed (REQ-MISSION-029). The default rule
   * notifies the unit's Einsatzmanager and officers.
   */
  OPERATION_COMPLETED,

  /**
   * An operation without an owning unit was completed (REQ-MISSION-029). The default rule notifies
   * every officer.
   */
  OPERATION_COMPLETED_UNOWNED,

  /**
   * A job order moved to another responsible unit (REQ-ORDERS-041). The default rule notifies the
   * officers, leads and logisticians of the new unit; the event clears the order's earlier creation
   * notices.
   */
  JOB_ORDER_REASSIGNED,

  /**
   * A job order was completed, rejected or deleted (REQ-ORDERS-042). The default rule notifies the
   * officers, leads and logisticians of the requesting unit.
   */
  JOB_ORDER_FINISHED,

  /**
   * Somebody assigned a member to a job order (REQ-ORDERS-043). The default rule notifies that
   * member.
   */
  JOB_ORDER_ASSIGNEE_ADDED,

  /**
   * A member was removed from a job order's assignees (REQ-ORDERS-043). Creates no notification;
   * clears that member's assignment notice.
   */
  JOB_ORDER_ASSIGNEE_REMOVED,

  /**
   * A material claim was withdrawn by an order edit or a de-escalation (REQ-ORDERS-044). The
   * default rule notifies the member who made the claim.
   */
  JOB_ORDER_CLAIM_WITHDRAWN,

  /**
   * A refinery order's run has ended and the output can be collected (REQ-REFINERY-023). Published
   * once by the time-based producer; the default rule notifies the order's owner.
   */
  REFINERY_ORDER_READY,

  /**
   * Somebody other than the owner changed, cancelled or stored a refinery order, or booked its
   * yield onto another member (REQ-REFINERY-024). The default rule notifies that member.
   */
  REFINERY_ORDER_CHANGED_BY_OTHER,

  /**
   * A refinery order was stored, cancelled or got a new run time (REQ-REFINERY-023). Creates no
   * notification; clears the order's collect-me notice.
   */
  REFINERY_ORDER_READY_CLEARED,

  /**
   * A Materialbörse offer left the board, withdrawn by its owner or because its stock is gone
   * (REQ-MARKET-021). The default rule notifies every member who registered interest; the event
   * clears the owner's interest notices.
   */
  MATERIAL_EXCHANGE_OFFER_UNAVAILABLE,

  /**
   * A Materialbörse request was withdrawn by its owner (REQ-MARKET-022). The default rule notifies
   * every member who signalled they can supply it; the event clears the owner's fulfilment notices.
   */
  MATERIAL_REQUEST_UNAVAILABLE,

  /**
   * Somebody other than the owner discarded or sold part of a member's Lager stock (REQ-INV-056).
   * The default rule notifies the owner.
   */
  INVENTORY_BOOKED_OUT_BY_OTHER,

  /**
   * The responsible holder approved an over-limit booking request, so it is ready to be confirmed
   * (REQ-BANK-057). The default rule notifies the bank employees granted on the account, the bank
   * management and the requester.
   */
  BANK_BOOKING_REQUEST_APPROVED,

  /**
   * The approval of a booking request was revoked (REQ-BANK-057). Creates no notification; clears
   * the request's ready-to-confirm notices.
   */
  BANK_BOOKING_REQUEST_APPROVAL_REVOKED,

  /**
   * A bank employee was granted access to an account or the grant's rights changed (REQ-BANK-058).
   * The default rule notifies the grantee.
   */
  BANK_GRANT_CHANGED,

  /**
   * A bank employee's access to an account was withdrawn (REQ-BANK-058). The default rule notifies
   * the former grantee.
   */
  BANK_GRANT_REVOKED,

  /**
   * Bank staff booked a withdrawal that pays out to a member (REQ-BANK-059). The default rule
   * notifies that member.
   */
  BANK_PAYOUT_BOOKED,

  /**
   * Bank staff moved aUEC from one holder to another (REQ-BANK-059). The default rule notifies the
   * receiving holder's member, who must take the aUEC over.
   */
  BANK_HOLDER_TRANSFER_BOOKED,

  /**
   * Bank staff debited an account directly or reversed a booking on it (REQ-BANK-059). The default
   * rule notifies the account's responsible holders.
   */
  BANK_ACCOUNT_DEBITED,

  /**
   * The roster reconciliation deactivated a bank holder who still holds aUEC (REQ-BANK-060). The
   * default rule notifies the bank management.
   */
  BANK_HOLDER_DEACTIVATED_WITH_BALANCE,

  /**
   * A deactivated holder was reactivated or their balance reached zero (REQ-BANK-060). Creates no
   * notification; clears the holder's deactivated-with-balance notice.
   */
  BANK_HOLDER_NOTICE_CLEARED,

  /**
   * A leadership seat was filled or vacated and the member's OFFICER role does not fit the new seat
   * set (REQ-ORG-029). The default rule notifies the admins.
   */
  ORG_LEADERSHIP_ROLE_MISMATCH,

  /**
   * A member's roles were reconciled and now fit their seats (REQ-ORG-029). Creates no
   * notification; clears the member's mismatch notice.
   */
  ORG_LEADERSHIP_ROLE_MISMATCH_CLEARED,

  /**
   * A member left the organisation (REQ-ORG-030). The default rule notifies the leadership of each
   * unit the member belonged to, and of the parent Bereich when a seat became vacant.
   */
  ORG_MEMBER_DEPARTED,

  /**
   * A ship was assigned to a mission unit by somebody other than its owner (REQ-HANGAR-005). The
   * default rule notifies the ship's owner.
   */
  HANGAR_SHIP_ASSIGNED_TO_UNIT,

  /**
   * A ship was taken off a mission unit (REQ-HANGAR-005). Creates no notification; clears the
   * owner's assignment notice for the unit.
   */
  HANGAR_SHIP_UNASSIGNED_FROM_UNIT,

  /**
   * A ship that was assigned to a mission unit of a mission that is not finished was deleted
   * (REQ-HANGAR-006). The default rule notifies the mission leadership and the unit's responsible
   * member.
   */
  HANGAR_SHIP_DELETED_FROM_MISSION,

  /**
   * The fitted marks of a member's ships were reset in bulk (REQ-HANGAR-007). The default rule
   * notifies that member.
   */
  HANGAR_FITTED_RESET_FOR_OWNER,

  /**
   * An admin added, changed or deleted a ship in a member's hangar (REQ-HANGAR-008). The default
   * rule notifies that member.
   */
  HANGAR_CHANGED_BY_ADMIN,

  /**
   * An admin added, changed, deleted or imported blueprints of a member (REQ-HANGAR-008). The
   * default rule notifies that member.
   */
  BLUEPRINT_CHANGED_BY_ADMIN,

  /**
   * An admin cleared the removable blueprints of every member (REQ-HANGAR-008). The default rule
   * notifies each member who lost some.
   */
  BLUEPRINT_PURGED_BY_ADMIN,

  /**
   * An admin suspended a connected application in the registry (REQ-XCH-040). The default rule
   * notifies the holders of its installations.
   */
  EXCHANGE_CLIENT_SUSPENDED,

  /**
   * An admin activated a suspended connected application again (REQ-XCH-040). The default rule
   * notifies its holders; the event clears the suspension notices.
   */
  EXCHANGE_CLIENT_ACTIVATED,

  /**
   * An admin raised the minimum client version of a connected application (REQ-XCH-040). The
   * default rule notifies its holders.
   */
  EXCHANGE_CLIENT_UPDATE_REQUIRED,

  /**
   * An admin removed capabilities from a connected application (REQ-XCH-040). The default rule
   * notifies its holders.
   */
  EXCHANGE_CLIENT_CAPABILITY_REMOVED,

  /**
   * An admin switched the whole exchange off (REQ-XCH-041). The default rule notifies the holders
   * of any installation.
   */
  EXCHANGE_SWITCHED_OFF,

  /**
   * An admin switched the exchange on again (REQ-XCH-041). Creates no notification; clears the
   * switched-off notices.
   */
  EXCHANGE_SWITCHED_ON
}
