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
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

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
 * <p><b>{@code audit_event.subject_label} is deliberately not selected.</b> It reads like a safe
 * column — REQ-AUDIT-001 describes it as a non-personal label, and for most domains it is one: a
 * material name, a rank step, an org-unit shorthand. For two it is a person. The job-order trails
 * build it as {@code #<displayId> '<handle>'}, where the handle is the order's <em>contact</em>
 * ("Handle des Ansprechpartners"), frequently an external customer with no account; and {@code
 * DeletionRequestService} writes a member's own effective name into it. {@link HandleScrubber}
 * could rescue neither: it is built from the roster, so a non-member contact is invisible to it and
 * a deleted member has already left it. Hence the column is <em>dropped</em> rather than scrubbed —
 * the projection is the only mechanism that works here. The timestamp, domain and event type carry
 * the Art. 15 substance; {@code subject_id} was never selected either, so the export did not
 * identify the object in the first place.
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
   * @param key the stable machine key, also the JSON property name and the suffix of the {@code
   *     pdf.export.section.*} label key in the backend message bundle
   * @param legalBasis {@link #ART_15} or {@link #ART_15_20}
   * @param rationale why the section carries this legal basis, in one sentence.
   *     <p><b>Not shipped, and that is the correction.</b> It used to be serialised into the
   *     member's JSON download as English prose, which the i18n rule forbids and which the PDF
   *     never rendered -- so it was user-visible text with no bundle key anywhere. The wire carries
   *     {@link #legalBasis} instead, which is machine-readable and locale-free; what the two bases
   *     mean is explained in the PDF, in the reader's language, and in {@code
   *     docs/privacy/processing-activities.md}. This field is the recorded reason for the
   *     classification, kept beside the statement it is about, and {@code DataExportSectionsTest}
   *     keeps every section carrying one.
   */
  public record Section(String key, String legalBasis, String rationale, String sql) {}

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
          new Section(
              "bankBookingsAsCounterparty",
              ART_15,
              "Bookings recording the member as the counterparty. Who booked them is not named,"
                  + " being a third party; the ledger itself is retained under Art. 6(1)(f).",
              """
              SELECT type, note, justification, transfer_fee, created_at
              FROM bank_transaction WHERE counterparty_user_id = :userId ORDER BY created_at
              """),
          new Section(
              "bankRequestsRaised",
              ART_15_20,
              "Raised by the member, including their own justification and note. The deciding bank"
                  + " employee is not named.",
              """
              SELECT type, amount, note, justification, status, created_at, decided_at
              FROM bank_booking_request WHERE requested_by = :userId ORDER BY created_at
              """),
          new Section(
              "auditActionsByMember",
              ART_15,
              "Actions the member performed, retained under Art. 6(1)(f) for up to 24 months"
                  + " (REQ-AUDIT-006). What each action was about is not named: that label can"
                  + " itself be a person.",
              """
              SELECT occurred_at, domain, event_type, client_id
              FROM audit_event WHERE actor_user_id = :userId ORDER BY occurred_at
              """),
          new Section(
              "auditActionsOnMember",
              ART_15,
              "Actions performed on the member by somebody else. Neither the acting person nor"
                  + " what the action was about is named, both being third parties.",
              """
              SELECT occurred_at, domain, event_type
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
   * The columns whose value is passed through {@link HandleScrubber}, keyed by section.
   *
   * <p><b>The gate is the column and not the section, and that is a correction.</b> It used to be
   * the section: every {@code String} of a listed section was scrubbed. That is fine for a section
   * whose columns are all prose, and wrong for every section that mixes prose with structured
   * values — most visibly {@code account}, which selects {@code username}, {@code email}, {@code
   * approval_status} and six more identity fields, all of them {@code String} on the wire. Another
   * member's three-character handle occurring inside the subject's own e-mail address was replaced
   * there, so the export handed the member a corrupted copy of their own identity. The subject's
   * own name is excluded from the scrubber's dictionary, which does nothing against somebody else's
   * handle being a substring of it.
   *
   * <p>The file's previous note argued the section-wide gate was the accepted side of a trade —
   * "the scrubber only fires on handles of three characters or more, and a collision corrupts one
   * value in one person's export, whereas the alternative discloses a name". The trade was real but
   * it was not necessary: naming the prose columns costs one line per column and gives up nothing.
   *
   * <p>Most of these carry free text the <em>member</em> wrote — a note, a remark, a description.
   * That is their own data and belongs in the export, and it may name somebody else mid-sentence
   * where no {@code SELECT} list can reach. Six database columns are listed for the mirror-image
   * reason: the text names a thing rather than being prose, and somebody may have named that thing
   * after a person — {@code ship.name}, {@code personal_inventory_item.name}, {@code mission.name}
   * (selected by three sections), {@code bank_account.name}, and {@code org_chart_position.name}
   * and {@code display_name}. Each of those is registered as a person-name surface in {@link
   * PersonSearchTargets}, which is the registry that settles the question.
   *
   * <p><b>This list cannot be the answer for every column.</b> It only reaches handles the scrubber
   * knows, which is registered members in all three of their spellings — never an external contact
   * and never an already-deleted member. Where those appear, the column has to be left unselected
   * instead; {@code audit_event.subject_label} is the worked example, in the class note above.
   *
   * @see #UNSCRUBBED_PERSON_COLUMNS for the columns that are a person-name surface and are still
   *     deliberately not scrubbed, each with its reason
   */
  public static final Map<String, Set<String>> FREE_TEXT_COLUMNS =
      Map.ofEntries(
          Map.entry("account", Set.of("description")),
          Map.entry("registrationDecisions", Set.of("reason")),
          Map.entry("deletionRequests", Set.of("decision_note")),
          Map.entry("warehouseContributions", Set.of("note")),
          Map.entry("hangar", Set.of("name")),
          Map.entry("personalInventory", Set.of("name", "note")),
          Map.entry("personalBlueprints", Set.of("note")),
          Map.entry("notifications", Set.of("params")),
          Map.entry("notificationRuleTargets", Set.of("rule")),
          Map.entry("missionsOwned", Set.of("name", "description", "meeting_point")),
          Map.entry("missionParticipations", Set.of("mission", "comment")),
          Map.entry("missionsManaged", Set.of("mission")),
          Map.entry("jobOrderAssignments", Set.of("note")),
          Map.entry("marketOffers", Set.of("remark")),
          Map.entry("marketRequests", Set.of("remark")),
          Map.entry("bankAccountGrants", Set.of("account")),
          Map.entry("bankBookingsAsCounterparty", Set.of("note", "justification")),
          Map.entry("bankRequestsRaised", Set.of("note", "justification")),
          Map.entry("orgChartPositions", Set.of("name", "display_name")));

  /**
   * Selected columns that {@link PersonSearchTargets} registers as a person-name surface and that
   * are nevertheless <b>not</b> scrubbed, each with the reason.
   *
   * <p>Keyed {@code section.column}. This is the other half of {@link #FREE_TEXT_COLUMNS}, and it
   * exists so that the two registries can be held against each other by a test rather than by
   * whoever reads them next: {@code DataExportScrubCoverageTest} walks every section's {@code
   * SELECT} list, and a column that names a person must appear in one map or the other. Without
   * that, a new section selecting a prose column is scrubbed or not depending on whether its author
   * remembered — which is precisely how {@code notificationRuleTargets} came to ship unscrubbed.
   *
   * <p>The pattern is the one {@code PersonSearchCoverageTest} uses for the search registry:
   * covered or exempted, never merely absent.
   */
  public static final Map<String, String> UNSCRUBBED_PERSON_COLUMNS =
      Map.ofEntries(
          Map.entry(
              "account.username",
              "The subject's own handle. Their own name is what the export is about, and"
                  + " substituting another member's handle inside it corrupts an identity field."),
          Map.entry("account.display_name", "The subject's own name; see account.username."),
          Map.entry(
              "account.email",
              "A structured identifier, and the subject's own. A three-character handle occurring"
                  + " inside a local part would have made the address unusable."),
          Map.entry(
              "account.discord_guild_nickname",
              "The subject's own nickname; see account.username."),
          Map.entry(
              "bankHolderRegistration.handle",
              "The subject's own custodian handle -- the registration is theirs, and the column is"
                  + " a snapshot of their own name."),
          Map.entry(
              "orgUnitMemberships.org_unit",
              "org_unit.name and org_unit.shorthand: an organisational unit, created by the"
                  + " organisation and not named after a member. Scrubbing it would corrupt the"
                  + " name of the unit the membership is in."),
          Map.entry("orgUnitMemberships.shorthand", "See orgUnitMemberships.org_unit."),
          Map.entry(
              "orgChartPositions.org_unit",
              "org_unit.name, the unit the position sits in; see orgUnitMemberships.org_unit."),
          Map.entry(
              "evaluations.category",
              "promotion_category.name: a catalogue entry maintained by the organisation, rendered"
                  + " in the promotion UI. A corrupted category name would misdescribe the"
                  + " assessment itself."),
          Map.entry(
              "warehouseContributions.location",
              "location.name: a station or outpost from the synced catalogue, not a person."),
          Map.entry(
              "warehouseContributions.material",
              "material.name: a commodity from the synced catalogue. Flagged only because the"
                  + " statement also reads location, whose name column is a person-name surface;"
                  + " the coverage check matches column names across a statement's tables on"
                  + " purpose, so an over-flag is answered here rather than by narrowing the"
                  + " check."),
          Map.entry(
              "hangar.ship_type",
              "ship_type.name: a hull from the synced catalogue. Flagged for the same reason as"
                  + " warehouseContributions.material -- the statement also reads ship, whose name"
                  + " column is the member's own and is scrubbed."),
          Map.entry("hangar.location", "location.name; see warehouseContributions.location."),
          Map.entry(
              "refineryOrdersOwned.location",
              "location.name; see warehouseContributions.location."),
          Map.entry(
              "materialClaims.job_order",
              "job_order.display_id, a number rather than a name. The order's handle column is"
                  + " deliberately not selected by any section, being an external contact's name"
                  + " the scrubber cannot see."));

  /**
   * Whether one selected column of one section is scrubbed.
   *
   * @param sectionKey the section's key
   * @param column the column alias as the statement yields it
   * @return {@code true} when the value goes through {@link HandleScrubber}
   */
  public static boolean isScrubbed(@NotNull String sectionKey, @NotNull String column) {
    return FREE_TEXT_COLUMNS.getOrDefault(sectionKey, Set.of()).contains(column);
  }
}
