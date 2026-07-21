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

import org.jetbrains.annotations.NotNull;

/**
 * Classifies an {@link AuditEvent} row across the audited areas (REQ-AUDIT-001). Each constant
 * carries the {@link AuditDomain} it belongs to, so the persisted {@code domain} column and the
 * event type can never disagree and the admin viewer's per-tab event filter is derived from the
 * enum itself. Like {@link BankAuditEventType} this is the source of truth and is deliberately NOT
 * mirrored by a database CHECK constraint (V113/V154 precedent): the set grows with the domains.
 */
public enum AuditEventType {

  // ---------------------------------------------------------------- INVENTORY (Lager) --
  /** A warehouse inventory row was created. */
  INVENTORY_ITEM_CREATED(AuditDomain.INVENTORY),

  /** A warehouse inventory row's associations / quality / amount were edited. */
  INVENTORY_ITEM_UPDATED(AuditDomain.INVENTORY),

  /**
   * Two or more matching warehouse rows were folded into one (stock merge): a {@code PIECE} write
   * merged automatically, or an {@code SCU} write opted in for that single action. The surviving
   * row absorbs the siblings' amounts and notes; offer-backed rows are never merged.
   */
  INVENTORY_ITEM_MERGED(AuditDomain.INVENTORY),

  /** A warehouse inventory row's free-text note was set or cleared. */
  INVENTORY_ITEM_NOTE_UPDATED(AuditDomain.INVENTORY),

  /**
   * A quantity slice earmarking part of a warehouse row to a job order or mission was added
   * (Variante C, REQ-INV-027). Independent of the entry-level create/update — an entry may hold
   * several such slices per dimension, each with its own amount.
   */
  INVENTORY_ALLOCATION_ADDED(AuditDomain.INVENTORY),

  /** An existing quantity slice's earmarked amount was changed (Variante C, REQ-INV-027). */
  INVENTORY_ALLOCATION_CHANGED(AuditDomain.INVENTORY),

  /**
   * A quantity slice was removed, releasing its amount back to the entry's unallocated remainder
   * (Variante C, REQ-INV-027).
   */
  INVENTORY_ALLOCATION_REMOVED(AuditDomain.INVENTORY),

  /** Stock was discarded/consumed (book-out type {@code DISCARD}); the row may be depleted. */
  INVENTORY_ITEM_CONSUMED(AuditDomain.INVENTORY),

  /** Stock was transferred to another owner/location (book-out type {@code TRANSFER}). */
  INVENTORY_ITEM_TRANSFERRED(AuditDomain.INVENTORY),

  /** Stock was sold, creating a mission finance entry (book-out type {@code SELL}). */
  INVENTORY_ITEM_SOLD(AuditDomain.INVENTORY),

  /**
   * Personal stock was rebooked into the shared squadron pool (Umbuchung): the source {@code
   * personal = true} row was split and the moved quantity inserted as a new {@code personal =
   * false} row stamped onto an org-unit pool.
   */
  INVENTORY_ITEM_DEPERSONALIZED(AuditDomain.INVENTORY),

  /**
   * Shared squadron stock was rebooked into the owner's personal pool (Umbuchung): the source
   * {@code personal = false} row was split and the moved quantity inserted as a new {@code personal
   * = true} row owned solely by that user.
   */
  INVENTORY_ITEM_PERSONALIZED(AuditDomain.INVENTORY),

  /** A material-collection row's delivered flag was toggled. */
  INVENTORY_ITEM_DELIVERY_TOGGLED(AuditDomain.INVENTORY),

  /** The caller bulk-checked-out several of their own inventory rows in one action. */
  INVENTORY_BULK_CHECKED_OUT(AuditDomain.INVENTORY),

  /** The admin emptied the whole (scoped) global warehouse — one summarizing event. */
  INVENTORY_WIPED(AuditDomain.INVENTORY),

  /** A new inventory row was created from refinery output (cross-domain store). */
  INVENTORY_RECEIVED_FROM_REFINERY(AuditDomain.INVENTORY),

  /**
   * A new game-item stock row was booked in from a job-order production run (cross-domain store,
   * REQ-INV-032): the produced units land in the Lager at the location/owner/org-unit the operator
   * chose in the Herstellung modal, optionally auto-earmarked to the producing order.
   */
  INVENTORY_RECEIVED_FROM_PRODUCTION(AuditDomain.INVENTORY),

  /** Inventory stock was decremented/deleted by a job-order handover (cross-domain). */
  INVENTORY_HANDED_OVER(AuditDomain.INVENTORY),

  /** Inventory stock was decremented/deleted by a job-order production booking (cross-domain). */
  INVENTORY_CONSUMED_BY_PRODUCTION(AuditDomain.INVENTORY),

  /** A user's inventory rows were re-stamped onto/off an org unit on a membership change. */
  INVENTORY_ORG_RESTAMPED(AuditDomain.INVENTORY),

  /** A deleted user's inventory rows were bulk-reassigned to the fallback admin. */
  INVENTORY_OWNER_REASSIGNED(AuditDomain.INVENTORY),

  /** The inventory audit log was exported as a PDF or JSON for a period. */
  INVENTORY_AUDIT_EXPORTED(AuditDomain.INVENTORY),

  /** Inventory audit rows older than an admin-chosen cutoff were purged (retention). */
  INVENTORY_AUDIT_PURGED(AuditDomain.INVENTORY),

  // ------------------------------------------------------------- JOB_ORDER (Aufträge) --
  /** A material job order was created. */
  JOB_ORDER_CREATED(AuditDomain.JOB_ORDER),

  /** An item job order was created. */
  JOB_ORDER_ITEM_CREATED(AuditDomain.JOB_ORDER),

  /** A material job order was edited (materials/handle/requesting unit). */
  JOB_ORDER_UPDATED(AuditDomain.JOB_ORDER),

  /** An item job order was edited (item lines rebuilt). */
  JOB_ORDER_ITEM_UPDATED(AuditDomain.JOB_ORDER),

  /** A job order's status was changed via the status endpoint. */
  JOB_ORDER_STATUS_CHANGED(AuditDomain.JOB_ORDER),

  /** A job order was moved to a new priority slot. */
  JOB_ORDER_PRIORITY_CHANGED(AuditDomain.JOB_ORDER),

  /**
   * An item order's blueprint-coverage variant-counting mode was toggled (with/without variants).
   */
  JOB_ORDER_BLUEPRINT_COUNTING_CHANGED(AuditDomain.JOB_ORDER),

  /** A job order was hard-deleted (label snapshotted into details before deletion). */
  JOB_ORDER_DELETED(AuditDomain.JOB_ORDER),

  /** A job order reached COMPLETED — the single funnel for manual and auto-completion. */
  JOB_ORDER_COMPLETED(AuditDomain.JOB_ORDER),

  /** A job order's responsible (processing) org unit was reassigned. */
  JOB_ORDER_REASSIGNED(AuditDomain.JOB_ORDER),

  /** A user was added as a job-order assignee. */
  JOB_ORDER_ASSIGNEE_ADDED(AuditDomain.JOB_ORDER),

  /** A user was removed as a job-order assignee. */
  JOB_ORDER_ASSIGNEE_REMOVED(AuditDomain.JOB_ORDER),

  /** An assignee's free-text note was set (note body never stored — length only). */
  JOB_ORDER_ASSIGNEE_NOTE_SET(AuditDomain.JOB_ORDER),

  /** An assignee's free-text note was cleared. */
  JOB_ORDER_ASSIGNEE_NOTE_CLEARED(AuditDomain.JOB_ORDER),

  /** A material requirement bucket was unlinked from a job order. */
  JOB_ORDER_MATERIAL_UNLINKED(AuditDomain.JOB_ORDER),

  /** A single inventory row was detached from a job order. */
  JOB_ORDER_INVENTORY_UNLINKED(AuditDomain.JOB_ORDER),

  /** A material handover was recorded against a job order. */
  JOB_ORDER_HANDOVER_CREATED(AuditDomain.JOB_ORDER),

  /** An item handover was recorded against a job order. */
  JOB_ORDER_ITEM_HANDOVER_CREATED(AuditDomain.JOB_ORDER),

  /** A production booking was recorded against an item order (units manufactured). */
  JOB_ORDER_PRODUCTION_BOOKED(AuditDomain.JOB_ORDER),

  /** A squadron claim on a public SK order was created or updated (upsert). */
  JOB_ORDER_CLAIM_UPSERTED(AuditDomain.JOB_ORDER),

  /** A squadron claim was withdrawn. */
  JOB_ORDER_CLAIM_WITHDRAWN(AuditDomain.JOB_ORDER),

  /** The job-order audit log was exported as a PDF or JSON for a period. */
  JOB_ORDER_AUDIT_EXPORTED(AuditDomain.JOB_ORDER),

  /** Job-order audit rows older than an admin-chosen cutoff were purged (retention). */
  JOB_ORDER_AUDIT_PURGED(AuditDomain.JOB_ORDER),

  // --------------------------------------------------------- REFINERY (Raffinerie) --
  /** A refinery order was created. */
  REFINERY_ORDER_CREATED(AuditDomain.REFINERY),

  /** A refinery order was edited (incl. status moves folded into details). */
  REFINERY_ORDER_UPDATED(AuditDomain.REFINERY),

  /** A refinery order was cancelled (soft-delete to status CANCELED). */
  REFINERY_ORDER_CANCELED(AuditDomain.REFINERY),

  /** A refinery order was completed, storing its yields to inventory. */
  REFINERY_ORDER_STORED(AuditDomain.REFINERY),

  /** An admin created a refining-method reference row. */
  REFINERY_METHOD_CREATED(AuditDomain.REFINERY),

  /** An admin edited a refining-method reference row (name/description). */
  REFINERY_METHOD_UPDATED(AuditDomain.REFINERY),

  /** An admin deleted a refining-method reference row. */
  REFINERY_METHOD_DELETED(AuditDomain.REFINERY),

  /** The scheduled UEX refining-method sync ran — one summarizing event per run. */
  REFINERY_METHODS_SYNCED(AuditDomain.REFINERY),

  /** The scheduled UEX refinery-yield sync ran — one summarizing event per run. */
  REFINERY_YIELDS_SYNCED(AuditDomain.REFINERY),

  /** A deleted user's refinery orders were bulk-reassigned to the fallback admin. */
  REFINERY_ORDERS_REASSIGNED(AuditDomain.REFINERY),

  /** The refinery audit log was exported as a PDF or JSON for a period. */
  REFINERY_AUDIT_EXPORTED(AuditDomain.REFINERY),

  /** Refinery audit rows older than an admin-chosen cutoff were purged (retention). */
  REFINERY_AUDIT_PURGED(AuditDomain.REFINERY),

  // ------------------------------------------------- PERSONAL_INVENTORY (Mein Inventar) --
  /** A personal inventory item was created (admin-on-behalf sets the target user). */
  PERSONAL_INVENTORY_CREATED(AuditDomain.PERSONAL_INVENTORY),

  /** A personal inventory item was updated. */
  PERSONAL_INVENTORY_UPDATED(AuditDomain.PERSONAL_INVENTORY),

  /** A personal inventory item was deleted. */
  PERSONAL_INVENTORY_DELETED(AuditDomain.PERSONAL_INVENTORY),

  /** The personal-inventory audit log was exported as a PDF or JSON for a period. */
  PERSONAL_INVENTORY_AUDIT_EXPORTED(AuditDomain.PERSONAL_INVENTORY),

  /** Personal-inventory audit rows older than an admin-chosen cutoff were purged (retention). */
  PERSONAL_INVENTORY_AUDIT_PURGED(AuditDomain.PERSONAL_INVENTORY),

  // ---------------------------------------------------------------- MISSION (Missionen) --
  /** A mission (or sub-mission) was created. */
  MISSION_CREATED(AuditDomain.MISSION),

  /** A mission's core / schedule / flags metadata was edited (details name the section). */
  MISSION_UPDATED(AuditDomain.MISSION),

  /** A mission was hard-deleted (its inventory/refinery links are detached, not deleted). */
  MISSION_DELETED(AuditDomain.MISSION),

  /** A participant (registered user or guest) joined or was added to a mission. */
  MISSION_PARTICIPANT_ADDED(AuditDomain.MISSION),

  /** A participant was removed from a mission. */
  MISSION_PARTICIPANT_REMOVED(AuditDomain.MISSION),

  /** A participant's attributes (job type, times, org units, payout preference) were edited. */
  MISSION_PARTICIPANT_UPDATED(AuditDomain.MISSION),

  /** A participant was checked in (start time stamped). */
  MISSION_PARTICIPANT_CHECKED_IN(AuditDomain.MISSION),

  /** A participant was checked out (end time stamped). */
  MISSION_PARTICIPANT_CHECKED_OUT(AuditDomain.MISSION),

  /** A unit (ship/team) was added to a mission. */
  MISSION_UNIT_ADDED(AuditDomain.MISSION),

  /** A mission unit was edited. */
  MISSION_UNIT_UPDATED(AuditDomain.MISSION),

  /** A mission unit was removed. */
  MISSION_UNIT_REMOVED(AuditDomain.MISSION),

  /** A participant was assigned as crew to a mission unit. */
  MISSION_CREW_ADDED(AuditDomain.MISSION),

  /** A crew assignment's roles were edited. */
  MISSION_CREW_UPDATED(AuditDomain.MISSION),

  /** A crew assignment was removed from a mission unit. */
  MISSION_CREW_REMOVED(AuditDomain.MISSION),

  /** A mission radio frequency was created or updated. */
  MISSION_FREQUENCY_CHANGED(AuditDomain.MISSION),

  /** A mission radio frequency was removed. */
  MISSION_FREQUENCY_REMOVED(AuditDomain.MISSION),

  /** A mission's owner was changed. */
  MISSION_OWNER_CHANGED(AuditDomain.MISSION),

  /** A mission's owning org unit (Staffel/SK/Bereich/OL, or ownerless) was reassigned. */
  MISSION_OWNING_ORG_UNIT_CHANGED(AuditDomain.MISSION),

  /** A mission's party lead (Veranstaltungsleiter) was set or cleared. */
  MISSION_PARTY_LEAD_CHANGED(AuditDomain.MISSION),

  /** A co-manager was added to a mission. */
  MISSION_MANAGER_ADDED(AuditDomain.MISSION),

  /** A co-manager was removed from a mission. */
  MISSION_MANAGER_REMOVED(AuditDomain.MISSION),

  /** A mission finance entry (income/expense) was created. */
  MISSION_FINANCE_ENTRY_CREATED(AuditDomain.MISSION),

  /** A mission finance entry was edited. */
  MISSION_FINANCE_ENTRY_UPDATED(AuditDomain.MISSION),

  /** A mission finance entry was deleted. */
  MISSION_FINANCE_ENTRY_DELETED(AuditDomain.MISSION),

  /** An Ablauf (procedure) step was added to a mission. */
  MISSION_STEP_ADDED(AuditDomain.MISSION),

  /** An Ablauf step's title or time/place hint was edited. */
  MISSION_STEP_UPDATED(AuditDomain.MISSION),

  /** An Ablauf step was removed from a mission. */
  MISSION_STEP_REMOVED(AuditDomain.MISSION),

  /** A mission's Ablauf steps were reordered (one event per reorder action). */
  MISSION_STEP_REORDERED(AuditDomain.MISSION),

  /** An Ablauf step's shared done flag was toggled on or off. */
  MISSION_STEP_DONE_CHANGED(AuditDomain.MISSION),

  /** A goal (Ziel) was added to a mission. */
  MISSION_OBJECTIVE_ADDED(AuditDomain.MISSION),

  /** A mission goal's text or classification was edited. */
  MISSION_OBJECTIVE_UPDATED(AuditDomain.MISSION),

  /** A goal was removed from a mission. */
  MISSION_OBJECTIVE_REMOVED(AuditDomain.MISSION),

  /** A mission's goals were reordered (one event per reorder action). */
  MISSION_OBJECTIVE_REORDERED(AuditDomain.MISSION),

  /** The mission audit log was exported as a PDF or JSON for a period. */
  MISSION_AUDIT_EXPORTED(AuditDomain.MISSION),

  /** Mission audit rows older than an admin-chosen cutoff were purged (retention). */
  MISSION_AUDIT_PURGED(AuditDomain.MISSION),

  // ------------------------------------------------------------ OPERATION (Operationen) --
  /** An operation was created. */
  OPERATION_CREATED(AuditDomain.OPERATION),

  /** An operation was edited (name / description / status; details name a status change). */
  OPERATION_UPDATED(AuditDomain.OPERATION),

  /** An operation was hard-deleted (its missions are unlinked, not deleted). */
  OPERATION_DELETED(AuditDomain.OPERATION),

  /** A participant's payout status on an operation was toggled paid / unpaid. */
  OPERATION_PAYOUT_TOGGLED(AuditDomain.OPERATION),

  /** The operation audit log was exported as a PDF or JSON for a period. */
  OPERATION_AUDIT_EXPORTED(AuditDomain.OPERATION),

  /** Operation audit rows older than an admin-chosen cutoff were purged (retention). */
  OPERATION_AUDIT_PURGED(AuditDomain.OPERATION),

  // -------------------------------------------------------- ROLE (Rollen & Mitglieder) --
  /** A user was added as a member of an org unit (Staffel / Spezialkommando). */
  MEMBERSHIP_GRANTED(AuditDomain.ROLE),

  /** A user's membership of an org unit was removed (incl. a Staffel move's old row). */
  MEMBERSHIP_REVOKED(AuditDomain.ROLE),

  /** A leadership rank was assigned to a membership that previously held none. */
  ROLE_GRANTED(AuditDomain.ROLE),

  /** A membership's existing leadership rank was changed to a different rank. */
  ROLE_CHANGED(AuditDomain.ROLE),

  /** A membership's leadership rank was revoked (back to a plain member, or the row deleted). */
  ROLE_REVOKED(AuditDomain.ROLE),

  /** The Logistician / Mission-Manager capability flags on a membership were changed. */
  CAPABILITY_FLAGS_CHANGED(AuditDomain.ROLE),

  /** A Kommandogruppe was created within a squadron. */
  KOMMANDO_GROUP_CREATED(AuditDomain.ROLE),

  /** A Kommandogruppe was renamed or reordered. */
  KOMMANDO_GROUP_UPDATED(AuditDomain.ROLE),

  /** A Kommandogruppe was deleted (its squadron-rank member links are cleared first). */
  KOMMANDO_GROUP_DELETED(AuditDomain.ROLE),

  /** The role &amp; membership audit log was exported as a PDF or JSON for a period. */
  ROLE_AUDIT_EXPORTED(AuditDomain.ROLE),

  /** Role &amp; membership audit rows older than an admin-chosen cutoff were purged (retention). */
  ROLE_AUDIT_PURGED(AuditDomain.ROLE),

  // ----------------------------------------------------------- PROMOTION (Beförderung) --
  /** A promotion topic (the catalogue's top-level grouping) was created. */
  PROMOTION_TOPIC_CREATED(AuditDomain.PROMOTION),

  /** A promotion topic's name / description / sort order was edited. */
  PROMOTION_TOPIC_UPDATED(AuditDomain.PROMOTION),

  /** A promotion topic was deleted (its categories and their level contents cascade-removed). */
  PROMOTION_TOPIC_DELETED(AuditDomain.PROMOTION),

  /** A promotion category was created under a topic. */
  PROMOTION_CATEGORY_CREATED(AuditDomain.PROMOTION),

  /** A promotion category was edited (name / description / sort order / topic re-binding). */
  PROMOTION_CATEGORY_UPDATED(AuditDomain.PROMOTION),

  /** A promotion category was deleted (its level contents cascade-removed). */
  PROMOTION_CATEGORY_DELETED(AuditDomain.PROMOTION),

  /** A promotion level content (the per-level rubric of a category) was created. */
  PROMOTION_LEVEL_CONTENT_CREATED(AuditDomain.PROMOTION),

  /** A promotion level content was edited (description / category re-binding). */
  PROMOTION_LEVEL_CONTENT_UPDATED(AuditDomain.PROMOTION),

  /** A promotion level content was deleted. */
  PROMOTION_LEVEL_CONTENT_DELETED(AuditDomain.PROMOTION),

  /** A rank requirement (the rules for one rank step) was created. */
  PROMOTION_RANK_REQUIREMENT_CREATED(AuditDomain.PROMOTION),

  /** A rank requirement was edited (ranks / minimum level / required count / references). */
  PROMOTION_RANK_REQUIREMENT_UPDATED(AuditDomain.PROMOTION),

  /** A rank requirement was deleted. */
  PROMOTION_RANK_REQUIREMENT_DELETED(AuditDomain.PROMOTION),

  /** A member's evaluation level for a category was assigned for the first time. */
  PROMOTION_EVALUATION_CREATED(AuditDomain.PROMOTION),

  /** A member's existing evaluation level for a category was changed. */
  PROMOTION_EVALUATION_UPDATED(AuditDomain.PROMOTION),

  /** A member's evaluation entry for a category was deleted (their standing removed). */
  PROMOTION_EVALUATION_DELETED(AuditDomain.PROMOTION),

  /** The promotion audit log was exported as a PDF or JSON for a period. */
  PROMOTION_AUDIT_EXPORTED(AuditDomain.PROMOTION),

  /** Promotion audit rows older than an admin-chosen cutoff were purged (retention). */
  PROMOTION_AUDIT_PURGED(AuditDomain.PROMOTION),

  // ------------------------------------------------------------- MARKET (Materialbörse) --
  /** A Lager row was released to the Materialbörse (an offer became publicly listed). */
  MARKET_OFFER_RELEASED(AuditDomain.MARKET),

  /** An offer was taken off the board (deactivated) by its owner. */
  MARKET_OFFER_DEACTIVATED(AuditDomain.MARKET),

  /** An offer's trade remark was edited by its owner. */
  MARKET_REMARK_UPDATED(AuditDomain.MARKET),

  /** A member registered interest in an offer ("Interesse anmelden"). */
  MARKET_INTEREST_REGISTERED(AuditDomain.MARKET),

  /** A member withdrew their interest from an offer ("Interesse zurückziehen"). */
  MARKET_INTEREST_WITHDRAWN(AuditDomain.MARKET),

  /**
   * A wanted-listing (Gesuch) was posted to the Materialbörse (a request became publicly listed).
   */
  MARKET_REQUEST_CREATED(AuditDomain.MARKET),

  /** A request's desired quantity / minimum quality / description was edited by its owner. */
  MARKET_REQUEST_UPDATED(AuditDomain.MARKET),

  /** A request was withdrawn from the board (deactivated) by its owner. */
  MARKET_REQUEST_DEACTIVATED(AuditDomain.MARKET),

  /** A member signalled they can supply a request ("Ich kann liefern"). */
  MARKET_REQUEST_INTEREST_SIGNALLED(AuditDomain.MARKET),

  /** A member withdrew their fulfilment signal from a request. */
  MARKET_REQUEST_INTEREST_WITHDRAWN(AuditDomain.MARKET),

  /** The Materialbörse audit log was exported as a PDF or JSON for a period. */
  MARKET_AUDIT_EXPORTED(AuditDomain.MARKET),

  /** Materialbörse audit rows older than an admin-chosen cutoff were purged (retention). */
  MARKET_AUDIT_PURGED(AuditDomain.MARKET);

  /** The functional area this event type belongs to; pins the persisted {@code domain} column. */
  private final AuditDomain domain;

  /**
   * Binds an event type to its functional area.
   *
   * @param domain the area this event type belongs to
   */
  AuditEventType(@NotNull AuditDomain domain) {
    this.domain = domain;
  }

  /**
   * The functional area this event type belongs to.
   *
   * @return the owning {@link AuditDomain}
   */
  public @NotNull AuditDomain domain() {
    return domain;
  }
}
