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
 * Every free-text column the admin Personensuche searches, and every text column deliberately left
 * out (REQ-SEC-060).
 *
 * <p>{@code PersonSearchCoverageTest} fails the build when a text column is neither searched nor
 * listed in {@link #EXEMPT_COLUMNS}.
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
   * The searched columns, in the order results are reported: the member record first, the audit
   * trails last.
   */
  public static final List<Target> TARGETS =
      List.of(
          new Target("MEMBER", "app_user", "username", "id", LINK_MEMBER),
          new Target("MEMBER", "app_user", "display_name", "id", LINK_MEMBER),
          new Target("MEMBER", "app_user", "discord_guild_nickname", "id", LINK_MEMBER),
          new Target("MEMBER", "app_user", "description", "id", LINK_MEMBER),
          new Target("MEMBER", "app_user", "email", "id", LINK_MEMBER),
          new Target("REGISTRATION", "user_approval_event", "reason", "id", null),
          new Target("DELETION_REQUEST", "deletion_request", "decision_note", "id", null),
          new Target("MISSION", "mission", "name", "id", LINK_MISSION),
          new Target("MISSION", "mission", "description", "id", LINK_MISSION),
          new Target("MISSION", "mission", "meeting_point", "id", LINK_MISSION),
          new Target("MISSION", "mission", "party_lead_guest_name", "id", LINK_MISSION),
          new Target("MISSION", "mission_participant", "guest_name", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_participant", "comment", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_objective", "title", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_step", "title", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_step", "meta", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_unit", "name", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_unit", "note", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_frequency", "name", "mission_id", LINK_MISSION),
          new Target("MISSION", "mission_finance_entry", "note", "mission_id", LINK_MISSION),
          new Target("OPERATION", "operation", "name", "id", LINK_OPERATION),
          new Target("OPERATION", "operation", "description", "id", LINK_OPERATION),
          new Target("JOB_ORDER", "job_order", "comment", "id", LINK_JOB_ORDER),
          new Target("JOB_ORDER", "job_order", "handle", "id", LINK_JOB_ORDER),
          new Target("JOB_ORDER", "job_order_assignees", "note", "job_order_id", LINK_JOB_ORDER),
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
          new Target("INVENTORY", "inventory_item", "note", "id", LINK_INVENTORY),
          new Target("PERSONAL_INVENTORY", "personal_inventory_item", "name", "id", null),
          new Target("PERSONAL_INVENTORY", "personal_inventory_item", "note", "id", null),
          new Target("PERSONAL_INVENTORY", "personal_blueprint", "note", "id", null),
          new Target("HANGAR", "ship", "name", "id", null),
          new Target("MARKET", "material_exchange_offer", "remark", "id", LINK_MARKET),
          new Target("MARKET", "material_exchange_request", "remark", "id", LINK_MARKET),
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
          new Target("ORG", "org_chart_position", "name", "id", LINK_ORG),
          new Target("ORG", "org_chart_position", "display_name", "id", LINK_ORG),
          new Target("ORG", "org_unit", "grand_admiral_display_name", "id", LINK_ORG),
          new Target("ORG", "org_unit", "name", "id", LINK_ORG),
          new Target("ORG", "org_unit", "shorthand", "id", LINK_ORG),
          new Target("ORG", "org_unit", "description", "id", LINK_ORG),
          new Target("ORG", "kommando_group", "name", "id", LINK_ORG),
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
          new Target("AUDIT", "audit_event", "actor_handle", "id", LINK_AUDIT),
          new Target("AUDIT", "audit_event", "subject_label", "id", LINK_AUDIT),
          new Target("AUDIT", "bank_audit_event", "actor_handle", "id", LINK_AUDIT),
          new Target("AUDIT", "audit_event", "details", "id", LINK_AUDIT),
          new Target("AUDIT", "bank_audit_event", "details", "id", LINK_AUDIT));

  /**
   * Text columns deliberately not searched, as {@code table.column}: synced catalogue data, codes
   * and enum-like values, and technical payloads.
   */
  public static final Set<String> EXEMPT_COLUMNS =
      Set.of(
          "audit_event.client_id",
          "bank_audit_event.client_id",
          "notification.params",
          "notification.entity_type",
          "p4k_import_job.error_message",
          "p4k_import_job.result_json",
          "p4k_import_job.source_filename",
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
          "role.code",
          "role.name",
          "role.description",
          "default_blueprint.created_by",
          "frequency_type.created_by",
          "frequency_type.updated_by",
          "mission_frequency.created_by",
          "mission_frequency.updated_by",
          "mission_objective.created_by",
          "mission_objective.updated_by",
          "mission_step.created_by",
          "mission_step.updated_by",
          "external_sync_report.aggregate",
          "external_sync_report.detail",
          "external_sync_report.event_type",
          "external_sync_report.external_name",
          "external_sync_report.source_system",
          "material_external_alias.source_system",
          "blueprint_external_alias.source_system",
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
   * The subset of {@link #EXEMPT_COLUMNS} that can contain a name but is not searched because a hit
   * there adds nothing, such as {@code notification.params}.
   *
   * <p>The Art. 15 export must scrub these columns; {@code PersonSearchCoverageTest} holds the set
   * inside {@code EXEMPT_COLUMNS}.
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
