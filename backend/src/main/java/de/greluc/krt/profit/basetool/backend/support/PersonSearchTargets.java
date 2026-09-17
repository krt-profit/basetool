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
import java.util.Set;

/**
 * Every free-text surface the admin Personensuche searches, and every text column deliberately left
 * out (REQ-SEC-060).
 *
 * <p><b>Why a registry and not a reflection sweep.</b> A name can sit in a column no foreign key
 * points at, so the search cannot be derived from the object graph. And "search everything of type
 * text" would sweep ~200 columns of synced catalogue data — planet names, manufacturer nicknames,
 * ship-type brochure URLs — which cannot contain a member's name in any sense that matters and
 * would bury the real hits. So the set is written down, and {@code PersonSearchCoverageTest} sweeps
 * {@code information_schema} and <b>fails the build</b> when a text column is neither searched nor
 * listed in {@link #EXEMPT_COLUMNS}. A new free-text column therefore cannot be added silently: the
 * author must decide which it is.
 *
 * <p><b>What "free text" means here.</b> Text a human typed into this application. The catalogue
 * tables are synced from UEX and the SC wiki; nobody types into them, and a member's handle
 * appearing in one would be a coincidence of spelling rather than a record about a person.
 */
public final class PersonSearchTargets {

  /** Not instantiable. */
  private PersonSearchTargets() {}

  /**
   * One searchable column.
   *
   * @param area the bounded area label, rendered via {@code admin.personSearch.area.*}
   * @param table the physical table
   * @param column the text column to match against, case-insensitively
   * @param idColumn the column holding the row's identity, so the hit can be linked
   * @param linkKind the bounded route key the frontend maps to a URL, or {@code null} when the row
   *     has no page of its own and the hit is informational only
   */
  public record Target(
      String area, String table, String column, String idColumn, String linkKind) {}

  /** Route keys the frontend knows how to turn into a link. Bounded on purpose. */
  public static final String LINK_MEMBER = "MEMBER";

  /** Route key for a mission detail page. */
  public static final String LINK_MISSION = "MISSION";

  /** Route key for an operation detail page. */
  public static final String LINK_OPERATION = "OPERATION";

  /** Route key for a job-order detail page. */
  public static final String LINK_JOB_ORDER = "JOB_ORDER";

  /** Route key for the warehouse. */
  public static final String LINK_INVENTORY = "INVENTORY";

  /** Route key for a bank account's detail page. */
  public static final String LINK_BANK = "BANK";

  /** Route key for the material exchange. */
  public static final String LINK_MARKET = "MARKET";

  /** Route key for the org chart. */
  public static final String LINK_ORG = "ORG";

  /** Route key for the audit viewer. */
  public static final String LINK_AUDIT = "AUDIT";

  /**
   * The searched columns, grouped by the area an admin thinks in.
   *
   * <p>Ordering is the order results are reported in, which is deliberate: the member record first,
   * because a hit there means the person has an account and every other right is easier to serve;
   * the audit trails last, because a hit there is a record *about* an action rather than a place a
   * name was entered.
   */
  public static final List<Target> TARGETS =
      List.of(
          // --- the member record itself -------------------------------------------------
          new Target("MEMBER", "app_user", "username", "id", LINK_MEMBER),
          new Target("MEMBER", "app_user", "display_name", "id", LINK_MEMBER),
          new Target("MEMBER", "app_user", "discord_guild_nickname", "id", LINK_MEMBER),
          new Target("MEMBER", "app_user", "description", "id", LINK_MEMBER),
          new Target("MEMBER", "app_user", "email", "id", LINK_MEMBER),
          // The admin's written assessment of an applicant. The sharpest free-text field in the
          // schema, and the reason REQ-SEC-057 bounds its retention.
          new Target("REGISTRATION", "user_approval_event", "reason", "id", null),
          new Target("DELETION_REQUEST", "deletion_request", "decision_note", "id", null),

          // --- Einsätze -----------------------------------------------------------------
          new Target("MISSION", "mission", "name", "id", LINK_MISSION),
          new Target("MISSION", "mission", "description", "id", LINK_MISSION),
          new Target("MISSION", "mission", "meeting_point", "id", LINK_MISSION),
          // An external party lead, named by hand because they have no account.
          new Target("MISSION", "mission", "party_lead_guest_name", "id", LINK_MISSION),
          // The canonical case this whole feature exists for: an external participant.
          new Target("MISSION", "mission_participant", "guest_name", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_participant", "comment", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_objective", "title", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_step", "title", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_step", "meta", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_unit", "name", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_unit", "note", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_frequency", "name", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_finance_entry", "note", "mission_id", LINK_MISSION),

          // --- Operationen --------------------------------------------------------------
          new Target("OPERATION", "operation", "name", "id", LINK_OPERATION),
          new Target("OPERATION", "operation", "description", "id", LINK_OPERATION),

          // --- Aufträge -----------------------------------------------------------------
          new Target("JOB_ORDER", "job_order", "comment", "id", LINK_JOB_ORDER),
          // The requesting member's handle, typed rather than referenced.
          new Target("JOB_ORDER", "job_order", "handle", "id", LINK_JOB_ORDER),
          new Target("JOB_ORDER", "job_order_assignees", "note", "job_order_id", LINK_JOB_ORDER),
          // Handover recipients: a handle with no user id beside it, which is why an erasure has to
          // match them by text (REQ-SEC-062).
          new Target(
              "JOB_ORDER",
              "job_order_handover",
              "recipient_handle",
              "job_order_id",
              LINK_JOB_ORDER),
          new Target(
              "JOB_ORDER",
              "job_order_handover",
              "recipient_squadron",
              "job_order_id",
              LINK_JOB_ORDER),
          new Target(
              "JOB_ORDER",
              "job_order_item_handover",
              "recipient_handle",
              "job_order_id",
              LINK_JOB_ORDER),

          // --- Lager / Mein Inventar / Blueprints ---------------------------------------
          new Target("INVENTORY", "inventory_item", "note", "id", LINK_INVENTORY),
          new Target("PERSONAL_INVENTORY", "personal_inventory_item", "name", "id", null),
          new Target("PERSONAL_INVENTORY", "personal_inventory_item", "note", "id", null),
          new Target("PERSONAL_INVENTORY", "personal_blueprint", "note", "id", null),

          // --- Hangar -------------------------------------------------------------------
          new Target("HANGAR", "ship", "name", "id", null),

          // --- Materialbörse ------------------------------------------------------------
          new Target("MARKET", "material_exchange_offer", "remark", "id", LINK_MARKET),
          new Target("MARKET", "material_exchange_request", "remark", "id", LINK_MARKET),

          // --- Bank ---------------------------------------------------------------------
          new Target("BANK", "bank_account", "name", "id", LINK_BANK),
          new Target("BANK", "bank_holder", "handle", "id", LINK_BANK),
          new Target("BANK", "bank_transaction", "justification", "id", LINK_BANK),
          new Target("BANK", "bank_transaction", "note", "id", LINK_BANK),
          new Target("BANK", "bank_transaction", "staff_note", "id", LINK_BANK),
          new Target("BANK", "bank_transaction", "counterparty_handle", "id", LINK_BANK),
          new Target("BANK", "bank_booking_request", "justification", "id", LINK_BANK),
          new Target("BANK", "bank_booking_request", "note", "id", LINK_BANK),
          new Target("BANK", "bank_booking_request", "staff_note", "id", LINK_BANK),
          new Target("BANK", "bank_booking_request", "reject_reason", "id", LINK_BANK),
          new Target("BANK", "bank_booking_request", "requester_handle", "id", LINK_BANK),
          new Target("BANK", "bank_booking_request", "decider_handle", "id", LINK_BANK),
          new Target("BANK", "bank_booking_request", "counterparty_handle", "id", LINK_BANK),
          new Target(
              "BANK", "bank_booking_request", "owner_approval_granted_by_handle", "id", LINK_BANK),

          // --- Organisation -------------------------------------------------------------
          // An org-chart placeholder for somebody without an account yet.
          new Target("ORG", "org_chart_position", "name", "id", LINK_ORG),
          new Target("ORG", "org_chart_position", "display_name", "id", LINK_ORG),
          new Target("ORG", "org_unit", "grand_admiral_display_name", "id", LINK_ORG),
          new Target("ORG", "org_unit", "name", "id", LINK_ORG),
          new Target("ORG", "org_unit", "shorthand", "id", LINK_ORG),
          new Target("ORG", "org_unit", "description", "id", LINK_ORG),
          new Target("ORG", "kommando_group", "name", "id", LINK_ORG),

          // --- Admin-authored reference text --------------------------------------------
          new Target("ANNOUNCEMENT", "announcement", "content", "id", null),
          new Target("LOCATION", "location", "name", "id", null),
          new Target("LOCATION", "location", "description", "id", null),
          new Target("MATERIAL", "material_external_alias", "note", "id", null),
          new Target("MATERIAL", "material_external_alias", "created_by", "id", null),
          new Target("MATERIAL", "blueprint_external_alias", "note", "id", null),
          new Target("MATERIAL", "blueprint_external_alias", "created_by", "id", null),
          new Target("PROMOTION", "promotion_topic", "name", "id", null),
          new Target("PROMOTION", "promotion_topic", "description", "id", null),
          new Target("PROMOTION", "promotion_category", "name", "id", null),
          new Target("PROMOTION", "promotion_category", "description", "id", null),
          new Target("PROMOTION", "promotion_level_content", "description", "id", null),
          new Target("PROMOTION", "rank_requirement", "description", "id", null),
          new Target("NOTIFICATION", "notification_rule", "description", "id", null),

          // --- the trails ---------------------------------------------------------------
          // Last, deliberately: a hit here is a record ABOUT an action, not a field somebody typed
          // a name into. It is also the only place an erasure cannot reach by id once the account
          // is gone (REQ-SEC-062).
          new Target("AUDIT", "audit_event", "actor_handle", "id", LINK_AUDIT),
          new Target("AUDIT", "audit_event", "subject_label", "id", LINK_AUDIT),
          new Target("AUDIT", "bank_audit_event", "actor_handle", "id", LINK_AUDIT),
          // Both details payloads, and they were exempt until review. The exemption read "carries
          // ids and counts, never user free text (REQ-AUDIT-001)", which is what the rule says and
          // not what the code does: `details` is a bare CharSequence on AuditService#record and
          // BankAuditService#record, so nothing routes a caller through the AuditDetails builder.
          // BankHolderService records HOLDER_REGISTERED with the holder's handle AS the payload,
          // and BankLedgerService writes "+<amount> aUEC @<handle>" on every booking. A search
          // that skipped these would report "no further mentions" for a name that is in them, and
          // the whole point of this registry is that such an answer cannot be wrong quietly.
          //
          // The cost is a hit that sometimes duplicates its own parent row's hit, which is the
          // reason the exemption gave for skipping them. A duplicate line in an admin's result
          // list is not comparable to a missed occurrence in an Art. 16 rectification.
          new Target("AUDIT", "audit_event", "details", "id", LINK_AUDIT),
          new Target("AUDIT", "bank_audit_event", "details", "id", LINK_AUDIT));

  /**
   * Text columns deliberately <b>not</b> searched, as {@code table.column}, each covered by one of
   * the reasons below. The coverage guard reads this set, so adding a column here is a decision
   * somebody has to write down rather than an omission.
   *
   * <ol>
   *   <li><b>Synced catalogue data</b> — UEX and SC-wiki rows nobody types into. A member handle in
   *       one would be a spelling coincidence, and searching ~200 such columns would bury every
   *       real hit.
   *   <li><b>Codes, keys, slugs, URLs and enum-like values</b> — not prose, and matching a name in
   *       one would be meaningless.
   *   <li><b>Technical payloads</b> — serialised JSON, import diagnostics, notification render
   *       params. These <em>can</em> contain a handle, and that is stated rather than hidden: they
   *       are machine-written, transient, and reachable through their owning record, so a name
   *       found there is not separately actionable.
   * </ol>
   */
  public static final Set<String> EXEMPT_COLUMNS =
      Set.of(
          // --- technical payloads, stated explicitly ------------------------------------
          // NOTE: audit_event.details and bank_audit_event.details used to be exempt here, on the
          // reason that they carry ids and counts and never user free text. They do carry names -
          // see the two targets above - so they are searched now. Do not re-exempt them without
          // first making the writers honour REQ-AUDIT-001.
          //
          // The originating-client label: a bounded vocabulary, never a name (REQ-AUDIT-005).
          "audit_event.client_id",
          "bank_audit_event.client_id",
          // Render parameters for one notification, machine-written from a bounded template.
          //
          // It does hold a handle - AccountDeletionRequestedEvent writes {"handle":"<name>"} into
          // one row per administrator - and that is why the exemption is about reachability and
          // not about absence: a notification belongs to one recipient, renders through a bounded
          // template, and is swept within 180 days (REQ-NOTIF-009). Searching it would return one
          // hit per admin inbox for the same event.
          //
          // A granted Art. 17 request used to rewrite the name inside this payload. That
          // statement was removed on 2026-09-17 -- it was a substring REPLACE over every row of
          // the table driven by the member's own self-service display name, so a short or common
          // one rewrote unrelated rows irreversibly (ADR-0183).
          //
          // The notification is superseded instead, which is better than rewriting it: the three
          // terminal transitions of a deletion request now resolve the pending notification, so
          // the row carrying the name is gone the moment the request is withdrawn, declined or
          // carried out rather than surviving until the 180-day unread sweep.
          "notification.params",
          "notification.entity_type",
          // P4K import diagnostics and the uploaded file's own name.
          "p4k_import_job.error_message",
          "p4k_import_job.result_json",
          "p4k_import_job.source_filename",

          // --- identifiers, codes and enum-like values ----------------------------------
          "app_user.discord_user_id",
          "bank_account.account_no",
          "bank_account.area_name",
          "bank_account_approval_limit.role_code",
          "bank_account_view_grant.role_code",
          "bank_booking_request.counterparty_org_unit_name",
          "bank_transaction.counterparty_org_unit_name",
          "notification_rule_selector.role_code",
          "operation_payout_status.participant_key",
          "system_setting.setting_key",
          "system_setting.setting_value",
          "terms_acceptance.terms_version",
          "mission.status",
          "mission.calendar_link",
          "ship.insurance",
          "material_external_alias.external_code",
          "material_external_alias.external_key",
          "material_external_alias.external_name",
          "blueprint_external_alias.external_name",
          "blueprint_external_alias.product_key",
          "blueprint_external_alias.product_name",
          "personal_blueprint.product_key",
          "personal_blueprint.product_name",
          "personal_inventory_item.location_name_snapshot",
          "material_exchange_offer.item_name",
          "material_exchange_offer.item_product_key",
          "material_exchange_request.item_name",
          "material_exchange_request.item_product_key",
          "job_order_handover_item.location_name",
          "material_category.name",
          "kommando_group.sort_key",
          "frequency_type.name",
          "frequency_type.description",
          "job_type.name",
          "job_type.description",
          "job_type.archetype",

          // --- @Enumerated(STRING) columns: an application enum, never a name ------------
          // Stored as varchar by JPA, so information_schema reports them as text. Each is a
          // bounded value set the code switches on; a person's name cannot appear in one.
          "app_user.approval_status",
          "app_user.default_payout_preference",
          "audit_event.domain",
          "audit_event.event_type",
          "bank_account.status",
          "bank_account.type",
          "bank_account_approval_limit.grantee_kind",
          "bank_account_view_grant.grantee_kind",
          "bank_audit_event.event_type",
          "bank_booking_request.required_approver",
          "bank_booking_request.status",
          "bank_booking_request.type",
          "bank_transaction.type",
          "deletion_request.status",
          "job_order.status",
          "job_order.type",
          "job_order_item_material.quality_requirement",
          "material_claim.quality_requirement",
          "material_exchange_offer.offer_kind",
          "material_exchange_offer.status",
          "material_exchange_request.request_kind",
          "material_exchange_request.status",
          "member_evaluation.assigned_level",
          "mission_finance_entry.type",
          "mission_objective.kind",
          "mission_participant.payout_preference",
          "notification.type",
          "notification_rule.event_type",
          "notification_rule.notification_type",
          "notification_rule_selector.context_role",
          "notification_rule_selector.kind",
          "notification_rule_selector.org_relative_role",
          "operation.status",
          "org_chart_position.position_type",
          "org_unit.department",
          "org_unit.kind",
          "org_unit_membership.kind",
          "org_unit_membership.role",
          "p4k_import_job.kind",
          "p4k_import_job.status",
          "personal_inventory_item.location_type",
          "promotion_level_content.level",
          "rank_requirement.minimum_level",
          "refinery_order.status",
          "role_permissions.permission",
          "user_approval_event.decision",

          // --- the role catalogue -------------------------------------------------------
          // Eight seeded rows describing roles, not people (DataInitializer). A member handle in
          // one would mean somebody had renamed a role after a person.
          "role.code",
          "role.name",
          "role.description",

          // --- authorship stamps that hold an id, not a name ----------------------------
          // These carry a Keycloak subject id or the literal "system". The two alias tables are
          // the exception and ARE searched: MaterialExternalAliasService writes the principal
          // NAME there, which can be a handle.
          "default_blueprint.created_by",
          "frequency_type.created_by",
          "frequency_type.updated_by",
          "mission_frequency.created_by",
          "mission_frequency.updated_by",
          "mission_objective.created_by",
          "mission_objective.updated_by",
          "mission_step.created_by",
          "mission_step.updated_by",

          // --- external-sync diagnostics ------------------------------------------------
          // Written by the UEX / SC-wiki sync jobs about catalogue rows, and reported on the
          // admin sync-report page. They name items and manufacturers, not members.
          "external_sync_report.aggregate",
          "external_sync_report.detail",
          "external_sync_report.event_type",
          "external_sync_report.external_name",
          "external_sync_report.source_system",
          "material_external_alias.source_system",
          "blueprint_external_alias.source_system",

          // --- blueprint catalogue detail rows ------------------------------------------
          // Recipe structure synced from the SC wiki: ingredient names, property keys, group
          // labels and comparison directions. The parent tables are in EXEMPT_TABLES; these are
          // their child tables, listed individually because their names do not share a prefix.
          "blueprint_dismantle_return.wiki_name_snapshot",
          "blueprint_ingredient.kind",
          "blueprint_ingredient.wiki_name_snapshot",
          "blueprint_requirement_group.group_key",
          "blueprint_requirement_group.kind",
          "blueprint_requirement_group.name",
          "blueprint_requirement_modifier.better_when",
          "blueprint_requirement_modifier.label",
          "blueprint_requirement_modifier.property_key",
          "blueprint_requirement_modifier.value_range_type",
          "blueprint_summary_property.better_when",
          "blueprint_summary_property.label",
          "blueprint_summary_property.property_key",
          "default_blueprint.product_key",
          "default_blueprint.product_name",
          "default_blueprint.scwiki_key");

  /**
   * The exemptions that rest on <b>reachability</b> rather than on absence: a name can be inside
   * these, and the reason they are not searched is that finding it there adds nothing.
   *
   * <p>A subset of {@link #EXEMPT_COLUMNS}, and the distinction matters outside the search. The
   * Art. 15 export has to know whether a column it selects can carry a third party's name, which is
   * a different question from whether searching it is useful: {@code notification.params} holds the
   * handle {@code AccountDeletionRequestedEvent} writes into one row per administrator, and the
   * export scrubs it for that reason while the search skips it because it would return one hit per
   * admin inbox for the same event.
   *
   * <p><b>Why it is a named set and not a sentence.</b> {@code DataExportScrubCoverageTest} asked
   * {@link #TARGETS} alone, so an exempt column left its question entirely: {@code
   * notifications.params} was scrubbed by the export and required by nothing, and deleting that one
   * line would have shipped the handle with both coverage gates passing (found 2026-09-17). Asking
   * the whole of {@code EXEMPT_COLUMNS} instead is no good either — most exemptions really are
   * enum-like values, codes and identifiers, and flagging ~40 of them would have buried the one
   * that matters. So the class is named here, next to the reasons it is drawn from, and {@code
   * PersonSearchCoverageTest} holds it inside {@code EXEMPT_COLUMNS}.
   *
   * <p>The other technical payloads in that block are here for the same reason: an import
   * diagnostic, its serialised result and the uploaded file's own name can each contain whatever
   * the uploader put there. {@code client_id} and {@code entity_type} are not, being bounded
   * vocabularies.
   */
  public static final Set<String> EXEMPT_BUT_MAY_HOLD_A_NAME =
      Set.of(
          "notification.params",
          "p4k_import_job.error_message",
          "p4k_import_job.result_json",
          "p4k_import_job.source_filename");

  /**
   * Table-name prefixes whose every text column is synced catalogue data, exempt wholesale.
   *
   * <p>Enumerating their ~200 columns individually would be a list nobody could keep true, and the
   * reason is the same for all of them: the rows come from UEX or the SC wiki on a schedule, nobody
   * types into them, and they describe places, ships, items and manufacturers rather than people.
   */
  public static final Set<String> EXEMPT_TABLES =
      Set.of(
          "city",
          "faction",
          "game_item",
          "game_item_price",
          "jurisdiction",
          "manufacturer",
          "material",
          "moon",
          "orbit",
          "outpost",
          "planet",
          "poi",
          "ship_type",
          "space_station",
          "star_system",
          "terminal",
          "uex_category",
          "uex_commodity",
          "uex_vehicle",
          "refining_method",
          "blueprint",
          "blueprint_component",
          "flyway_schema_history");
}
