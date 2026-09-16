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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.List;

/**
 * Every section of the Art. 15 / Art. 20 data export, as the SQL that produces it (REQ-SEC-058).
 *
 * <p><b>The projections are the anonymisation.</b> Third-party data is kept out of the export by
 * <em>not selecting it</em>, not by scrubbing it afterwards: each statement lists the columns it
 * returns, and no statement selects another member's id or handle. A redaction pass that had to
 * find other people's names in already-selected rows would miss some, and nobody would know which.
 *
 * <p>Free text the requester wrote is a separate problem and is handled separately, by {@link
 * HandleScrubber} — it is the requester's own data and belongs in the export, but it may name
 * somebody else inside the prose, where no projection can reach.
 *
 * <p><b>Each section carries its legal basis</b>, because the two rights are not the same set:
 *
 * <ul>
 *   <li>{@link #ART_15} — the right of access. Everything.
 *   <li>{@link #ART_15_20} — additionally portable under Art. 20: data the member <em>provided</em>
 *       themselves, processed on consent or contract. A reader can therefore pick the portable
 *       subset out of the export without re-deriving which sections qualify.
 * </ul>
 *
 * <p><b>What is deliberately out of scope</b>, and stated as such in {@code
 * docs/privacy/data-subject-requests.md} so the export and the record agree: platform logs, metrics
 * and traces; backups; and Keycloak's own record of the account. Each is either not retrievable per
 * person by design or belongs to a different controller surface.
 */
public final class DataExportSections {

  /** Not instantiable. */
  private DataExportSections() {}

  /** Legal basis marker: covered by the right of access only. */
  public static final String ART_15 = "ART_15";

  /** Legal basis marker: covered by access <em>and</em> portable under Art. 20. */
  public static final String ART_15_20 = "ART_15_20";

  /**
   * One export section.
   *
   * @param key the stable machine key, also the JSON property name and the {@code
   *     privacy.export.section.*} label key
   * @param legalBasis {@link #ART_15} or {@link #ART_15_20}
   * @param portableHint short prose on why the section is or is not portable, carried into the
   *     export itself so the document explains its own structure
   * @param sql the statement, with exactly one {@code :userId} parameter
   */
  public record Section(String key, String legalBasis, String portableHint, String sql) {}

  /**
   * The sections, in the order they appear in the export.
   *
   * <p>Ordered as a person would read it: who they are, what they agreed to, what they own, what
   * they took part in, what the bank recorded, and last what the trails say about them.
   */
  public static final List<Section> SECTIONS =
      List.of(
          new Section(
              "account",
              ART_15_20,
              "Provided at registration and on the profile page.",
              """
              SELECT id, username, display_name, email, description, user_rank, join_date,
                     discord_user_id, discord_guild_nickname, default_payout_preference,
                     share_blueprints_globally, approval_status, approved_at, created_at, updated_at
              FROM app_user WHERE id = :userId
              """),
          new Section(
              "termsAcceptances",
              ART_15,
              "A record of consent, not data the member supplied.",
              """
              SELECT terms_version, accepted_at
              FROM terms_acceptance WHERE user_id = :userId ORDER BY accepted_at
              """),
          // The decision and its reason are an assessment OF the member, so Art. 15 covers them and
          // they must be disclosed. The deciding admin is NOT selected: who decided is that admin's
          // data, and the member's right is to their own.
          new Section(
              "registrationDecisions",
              ART_15,
              "An assessment of the member by an administrator; the deciding administrator is not"
                  + " named, being a third party.",
              """
              SELECT decision, reason, created_at
              FROM user_approval_event WHERE user_id = :userId ORDER BY created_at
              """),
          new Section(
              "deletionRequests",
              ART_15,
              "The member's own erasure requests and their outcome.",
              """
              SELECT status, erase_history_requested, decision_note, created_at, decided_at
              FROM deletion_request WHERE user_id = :userId ORDER BY created_at
              """),
          new Section(
              "orgUnitMemberships",
              ART_15,
              "Assigned by the organisation, not provided by the member.",
              """
              SELECT o.name AS org_unit, o.shorthand, m.kind, m.role, m.is_logistician,
                     m.is_mission_manager, m.joined_at
              FROM org_unit_membership m JOIN org_unit o ON o.id = m.org_unit_id
              WHERE m.user_id = :userId ORDER BY m.joined_at
              """),
          new Section(
              "orgChartPositions",
              ART_15,
              "A placement in the org chart, assigned by the organisation.",
              """
              SELECT p.position_type, p.name, p.display_name, o.name AS org_unit, p.created_at
              FROM org_chart_position p LEFT JOIN org_unit o ON o.id = p.org_unit_id
              WHERE p.user_id = :userId ORDER BY p.created_at
              """),
          new Section(
              "evaluations",
              ART_15,
              "A grading of the member by the organisation.",
              """
              SELECT c.name AS category, e.assigned_level, e.updated_at
              FROM member_evaluation e LEFT JOIN promotion_category c ON c.id = e.category_id
              WHERE e.user_id = :userId ORDER BY e.updated_at
              """),
          new Section(
              "warehouseContributions",
              ART_15_20,
              "Entered by the member; the note is their own free text.",
              """
              SELECT i.amount, i.quality, i.personal, i.note, m.name AS material,
                     l.name AS location, i.created_at
              FROM inventory_item i
                LEFT JOIN material m ON m.id = i.material_id
                LEFT JOIN location l ON l.id = i.location_id
              WHERE i.user_id = :userId ORDER BY i.created_at
              """),
          new Section(
              "hangar",
              ART_15_20,
              "Entered by the member.",
              """
              SELECT s.name, t.name AS ship_type, s.insurance, s.fitted, l.name AS location,
                     s.created_at
              FROM ship s
                LEFT JOIN ship_type t ON t.id = s.ship_type_id
                LEFT JOIN location l ON l.id = s.location_id
              WHERE s.owner_id = :userId ORDER BY s.created_at
              """),
          new Section(
              "personalInventory",
              ART_15_20,
              "Entered by the member, including free-text notes.",
              """
              SELECT name, quantity, location_type, location_name_snapshot, note, created_at
              FROM personal_inventory_item WHERE owner_user_id = :userId ORDER BY created_at
              """),
          new Section(
              "personalBlueprints",
              ART_15_20,
              "Entered by the member.",
              """
              SELECT product_name, acquired_at, note, created_at
              FROM personal_blueprint WHERE owner_user_id = :userId ORDER BY created_at
              """),
          new Section(
              "notifications",
              ART_15,
              "Generated by the system for the member. The render parameters can name whoever"
                  + " triggered the event and are therefore scrubbed.",
              """
              SELECT type, entity_type, params, is_read, read_at, created_at
              FROM notification WHERE recipient_user_id = :userId ORDER BY created_at
              """),
          new Section(
              "notificationRuleTargets",
              ART_15,
              "Rules that name the member as a recipient; configured by an administrator.",
              """
              SELECT r.description AS rule, s.kind
              FROM notification_rule_selector s JOIN notification_rule r ON r.id = s.rule_id
              WHERE s.user_id = :userId ORDER BY r.description
              """),
          new Section(
              "missionsOwned",
              ART_15_20,
              "Created by the member; the description is their own free text.",
              """
              SELECT name, description, status, meeting_point, planned_start_time,
                     planned_end_time, created_at
              FROM mission WHERE owner_id = :userId ORDER BY created_at
              """),
          // The mission's OTHER participants are not selected at all: this returns the requester's
          // own row and the mission it belongs to, and nothing about who else was there.
          new Section(
              "missionParticipations",
              ART_15_20,
              "The member's own sign-up and comment. Other participants of the same mission are"
                  + " not included: they are other people.",
              """
              SELECT m.name AS mission, p.comment, p.payout_preference,
                     p.is_mission_lead_participant, p.start_time, p.end_time, p.created_at
              FROM mission_participant p JOIN mission m ON m.id = p.mission_id
              WHERE p.user_id = :userId ORDER BY p.created_at
              """),
          new Section(
              "missionsManaged",
              ART_15,
              "Assigned by the organisation.",
              """
              SELECT m.name AS mission, m.status
              FROM mission_managers mm JOIN mission m ON m.id = mm.mission_id
              WHERE mm.user_id = :userId ORDER BY m.name
              """),
          new Section(
              "refineryOrdersOwned",
              ART_15_20,
              "Created by the member.",
              """
              SELECT r.status, r.started_at, r.duration_minutes, r.expenses, r.ore_sales,
                     l.name AS location, r.created_at
              FROM refinery_order r LEFT JOIN location l ON l.id = r.location_id
              WHERE r.owner_id = :userId ORDER BY r.created_at
              """),
          new Section(
              "jobOrderAssignments",
              ART_15_20,
              "The member's own assignment note.",
              """
              SELECT o.display_id, o.type, o.status, a.note, a.created_at
              FROM job_order_assignees a JOIN job_order o ON o.id = a.job_order_id
              WHERE a.user_id = :userId ORDER BY a.created_at
              """),
          new Section(
              "materialClaims",
              ART_15_20,
              "Signed up for by the member.",
              """
              SELECT o.display_id AS job_order, m.name AS material, c.amount,
                     c.quality_requirement, c.created_at
              FROM material_claim c
                LEFT JOIN job_order o ON o.id = c.job_order_id
                LEFT JOIN material m ON m.id = c.material_id
              WHERE c.claimed_by_user_id = :userId ORDER BY c.created_at
              """),
          new Section(
              "marketOffers",
              ART_15_20,
              "Posted by the member, including their own remark.",
              """
              SELECT offer_kind, item_name, offered_amount, remark, status, released_at, created_at
              FROM material_exchange_offer WHERE owner_id = :userId ORDER BY created_at
              """),
          new Section(
              "marketRequests",
              ART_15_20,
              "Posted by the member, including their own remark.",
              """
              SELECT request_kind, item_name, requested_amount, min_quality, remark, status,
                     posted_at, created_at
              FROM material_exchange_request WHERE owner_id = :userId ORDER BY created_at
              """),
          // The OFFER's owner is deliberately not selected: an interest pairs the requester with a
          // counterparty, and the counterparty is somebody else.
          new Section(
              "marketInterests",
              ART_15_20,
              "Interest the member registered. The offering member is not named, being the"
                  + " counterparty.",
              """
              SELECT o.item_name, o.offer_kind, i.created_at
              FROM material_exchange_interest i JOIN material_exchange_offer o ON o.id = i.offer_id
              WHERE i.interested_user_id = :userId ORDER BY i.created_at
              """),
          // bank_holder is the bank's GLOBAL custodian registry -- one row per player, not one per
          // account (V151). So this reports the member's registry entry; the per-account rights are
          // the grants below.
          new Section(
              "bankHolderRegistration",
              ART_15,
              "The bank's record of the member as a custodian of org money.",
              """
              SELECT handle, active, role_managed, created_at
              FROM bank_holder WHERE user_id = :userId ORDER BY created_at
              """),
          new Section(
              "bankAccountGrants",
              ART_15,
              "Per-account viewing rights granted to the member by the bank.",
              """
              SELECT a.account_no, a.name AS account, g.created_at
              FROM bank_account_view_grant g JOIN bank_account a ON a.id = g.account_id
              WHERE g.grantee_user_id = :userId ORDER BY g.created_at
              """),
          // The member as the counterparty of a booking. `initiated_by` is NOT selected: the bank
          // employee who booked it is a third party.
          new Section(
              "bankBookingsAsCounterparty",
              ART_15,
              "Bookings recording the member as the counterparty. Who booked them is not named,"
                  + " being a third party; the ledger itself is retained under Art. 6(1)(f).",
              """
              SELECT type, note, justification, transfer_fee, created_at
              FROM bank_transaction WHERE counterparty_user_id = :userId ORDER BY created_at
              """),
          // Requests the member raised. The decider and the counterparty are not selected.
          new Section(
              "bankRequestsRaised",
              ART_15_20,
              "Raised by the member, including their own justification and note. The deciding bank"
                  + " employee is not named.",
              """
              SELECT type, amount, note, justification, status, created_at, decided_at
              FROM bank_booking_request WHERE requested_by = :userId ORDER BY created_at
              """),
          // Actions the member performed. subject_label is a non-personal label by design
          // (REQ-AUDIT-001); actor_handle is the requester's own and is redundant, so it is
          // omitted.
          new Section(
              "auditActionsByMember",
              ART_15,
              "Actions the member performed, retained under Art. 6(1)(f) for up to 24 months"
                  + " (REQ-AUDIT-006).",
              """
              SELECT occurred_at, domain, event_type, subject_label, client_id
              FROM audit_event WHERE actor_user_id = :userId ORDER BY occurred_at
              """),
          // Actions performed ON the member. actor_handle is NOT selected -- the acting admin is
          // another person, and this is the section where that distinction matters most.
          new Section(
              "auditActionsOnMember",
              ART_15,
              "Actions performed on the member by somebody else. The acting person is not named,"
                  + " being a third party.",
              """
              SELECT occurred_at, domain, event_type, subject_label
              FROM audit_event WHERE target_user_id = :userId ORDER BY occurred_at
              """),
          new Section(
              "bankAuditActionsByMember",
              ART_15,
              "Bank actions the member performed, retained under Art. 6(1)(f).",
              """
              SELECT occurred_at, event_type, client_id
              FROM bank_audit_event WHERE actor_user_id = :userId ORDER BY occurred_at
              """),
          new Section(
              "bankAuditActionsOnMember",
              ART_15,
              "Bank actions performed on the member by somebody else. The acting person is not"
                  + " named, being a third party.",
              """
              SELECT occurred_at, event_type
              FROM bank_audit_event WHERE target_user_id = :userId ORDER BY occurred_at
              """));

  /**
   * Section keys whose rows carry free text the member wrote, which may name somebody else inside
   * the prose and is therefore passed through {@link HandleScrubber}.
   *
   * <p>Listing them rather than scrubbing everything is deliberate: scrubbing a structured value —
   * a material name, a status, an account number — could corrupt it if a member's handle happened
   * to be a substring, and a corrupted export is worse than a verbose one.
   */
  public static final List<String> FREE_TEXT_SECTIONS =
      List.of(
          "account",
          "registrationDecisions",
          "deletionRequests",
          "warehouseContributions",
          "personalInventory",
          "personalBlueprints",
          "notifications",
          "missionsOwned",
          "missionParticipations",
          "jobOrderAssignments",
          "marketOffers",
          "marketRequests",
          "bankBookingsAsCounterparty",
          "bankRequestsRaised");
}
