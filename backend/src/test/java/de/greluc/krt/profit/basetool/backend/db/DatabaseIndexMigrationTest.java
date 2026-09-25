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

package de.greluc.krt.profit.basetool.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies that the indexes introduced by the Flyway migrations actually exist in the test
 * database. The test profile now boots Postgres via Testcontainers and runs every V<n>__*.sql
 * migration during context startup (see {@code application-test.yml}), so this test no longer needs
 * its own container/dynamic-property wiring nor the {@code ENABLE_TC} gate that historically kept
 * it from running in the default build — it is part of the standard test suite now.
 */
@SpringBootTest
class DatabaseIndexMigrationTest {

  @MockitoBean private RoleRepository roleRepository;

  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private DataSource dataSource;

  /**
   * Spot-checks a handful of indexes from the Flyway migrations to make sure they are actually
   * present in the live Postgres test schema. Picks one representative index from each migration
   * that introduces a non-trivial indexing strategy:
   *
   * <ul>
   *   <li>V34 (foreign-key b-tree index, e.g. {@code idx_ship_owner_id})
   *   <li>V35 (pg_trgm GIN index used by the ILIKE search endpoints)
   *   <li>V48 (mission owner/manager indexes added with the ownership rewrite)
   *   <li>V65 (personal inventory composite owner+name index)
   *   <li>V92 (backfill FK indexes that escaped V34's blanket sweep)
   *   <li>V122 (second FK backfill: handover / unit / yield lookup indexes)
   * </ul>
   *
   * The test is intentionally not exhaustive: it acts as an early-warning canary that Flyway
   * actually ran and produced the expected DDL. A missing index here is almost always a sign that a
   * migration was renamed/squashed without updating the index name.
   */
  @Test
  void flywayMigrationAddsExpectedIndexes() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertIndexExists(jdbc, "ship", "idx_ship_owner_id");
    assertIndexExists(jdbc, "inventory_item", "idx_inventory_item_material_id");
    assertIndexExists(jdbc, "mission", "idx_mission_name_trgm");
    assertIndexExists(jdbc, "material", "idx_material_name_trgm");
    assertIndexExists(jdbc, "mission", "idx_mission_owner");
    assertIndexExists(jdbc, "personal_inventory_item", "idx_personal_inventory_item_owner_name");
    assertIndexExists(jdbc, "mission", "idx_mission_operation_id");
    assertIndexExists(jdbc, "mission_participant", "uq_mission_participant_user");
    assertIndexExists(jdbc, "job_order_handover", "idx_job_order_handover_job_order_id");
    assertIndexExists(jdbc, "job_order_handover_item", "idx_job_order_handover_item_handover_id");
    assertIndexExists(jdbc, "refinery_yield", "idx_refinery_yield_terminal_material");
    assertIndexExists(jdbc, "inventory_item", "idx_inventory_item_stack_key");
    assertIndexExists(jdbc, "inventory_item", "idx_inventory_item_item_stack_key");
    assertIndexDefContains(
        jdbc,
        "inventory_item",
        "idx_inventory_item_item_stack_key",
        "game_item_id",
        "user_id",
        "location_id",
        "personal",
        "owning_org_unit_id",
        "where (game_item_id is not null)");
    assertIndexExists(
        jdbc, "material_external_alias", "uq_material_external_alias_source_lower_name");
    assertIndexExists(jdbc, "bank_account", "uq_bank_account_org_unit");
    assertIndexExists(jdbc, "bank_account", "uq_bank_account_singleton_cartel");
    assertIndexExists(jdbc, "bank_account", "uq_bank_account_singleton_cartel_bank");
    assertIndexExists(jdbc, "bank_account_grant", "idx_bank_account_grant_account");
    assertIndexExists(jdbc, "bank_posting", "idx_bank_posting_account_created");
    assertIndexExists(jdbc, "bank_posting", "idx_bank_posting_transaction");
    assertIndexExists(jdbc, "bank_holder_posting", "idx_bank_holder_posting_holder_created");
    assertIndexExists(jdbc, "bank_holder_posting", "idx_bank_holder_posting_transaction");
    assertIndexExists(jdbc, "bank_audit_event", "idx_bank_audit_event_occurred");
    assertIndexExists(jdbc, "bank_audit_event", "idx_bank_audit_event_account");
    assertIndexExists(
        jdbc, "manufacturer_uex_company", "idx_manufacturer_uex_company_manufacturer");
    assertIndexExists(jdbc, "job_order_assignees", "idx_job_order_assignees_user_id");
    assertIndexExists(jdbc, "bank_transaction", "idx_bank_transaction_initiated_by");
    assertIndexExists(jdbc, "app_user", "idx_app_user_approved_by_id");
    assertIndexExists(jdbc, "app_user", "idx_app_user_pending_approval");
    assertIndexExists(jdbc, "job_order", "idx_job_order_active_priority");
    assertIndexDefContains(
        jdbc,
        "app_user",
        "idx_app_user_pending_approval",
        "created_at",
        "approval_status",
        "'PENDING'");
    assertIndexDefContains(
        jdbc,
        "job_order",
        "idx_job_order_active_priority",
        "priority",
        "display_id DESC",
        "status",
        "'OPEN'",
        "'IN_PROGRESS'");
    assertIndexExists(jdbc, "material_exchange_request", "idx_material_exchange_request_status");
    assertIndexExists(jdbc, "material_exchange_request", "idx_material_exchange_request_owner");
    assertIndexExists(jdbc, "audit_event", "idx_audit_event_target");
    assertIndexExists(jdbc, "bank_audit_event", "idx_bank_audit_event_actor");
    assertIndexExists(jdbc, "bank_audit_event", "idx_bank_audit_event_target");
    assertIndexDefContains(
        jdbc,
        "notification",
        "idx_notification_created_unread",
        "created_at",
        "where (is_read = false)");
    assertIndexExists(
        jdbc, "material_exchange_request_interest", "uq_material_exchange_request_interest");
    assertIndexExists(jdbc, "material_claim", "idx_material_claim_claimed_by_user_id");
    assertIndexExists(jdbc, "mission_unit", "idx_mission_unit_responsible_user_id");
    assertIndexExists(jdbc, "org_unit", "idx_org_unit_grand_admiral_user_id");
    assertIndexExists(
        jdbc, "material_exchange_offer", "idx_material_exchange_offer_owning_org_unit_id");
    assertIndexExists(
        jdbc, "material_exchange_request", "idx_material_exchange_request_owning_org_unit_id");
    assertIndexExists(jdbc, "job_order_handover", "idx_job_order_handover_executing_user_id");
    assertIndexExists(jdbc, "bank_account_grant", "idx_bank_account_grant_granted_by");
    assertIndexExists(jdbc, "bank_booking_request", "idx_bank_booking_request_decided_by");
    assertIndexExists(
        jdbc, "bank_booking_request", "idx_bank_booking_request_resulting_transaction_id");
    assertIndexDefContains(
        jdbc, "mission_crew_job_types", "idx_mission_crew_job_types_job_type_id", "job_type_id");
    assertIndexDefContains(
        jdbc,
        "bank_transaction",
        "idx_bank_transaction_counterparty_user_id",
        "counterparty_user_id",
        "where (counterparty_user_id is not null)");
  }

  private static void assertIndexExists(JdbcTemplate jdbc, String table, String indexName) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = ? AND indexname = ?",
            String.class,
            table,
            indexName);
    assertThat(rows)
        .as("Expected Flyway-managed index %s on table %s to be present", indexName, table)
        .isNotEmpty();
  }

  /**
   * Pins the live {@code CREATE INDEX} definition of {@code indexName} on {@code table} by
   * asserting (case-insensitively) that every given fragment appears in its {@code pg_get_indexdef}
   * text. Unlike {@link #assertIndexExists}, which only proves the name is present, this locks the
   * index <em>shape</em>: the partial-index {@code WHERE} predicate and the key-column ordering. It
   * catches a migration that keeps the index name but silently narrows the predicate or flips a
   * sort direction — a change that would otherwise pass green while no longer serving the query the
   * index was added for.
   *
   * @param jdbc the template bound to the live Postgres test schema
   * @param table the table owning the index
   * @param indexName the index whose {@code pg_indexes.indexdef} text is inspected
   * @param fragments substrings, each matched case-insensitively, that must all appear in the
   *     definition
   */
  private static void assertIndexDefContains(
      JdbcTemplate jdbc, String table, String indexName, String... fragments) {
    List<String> defs =
        jdbc.queryForList(
            "SELECT indexdef FROM pg_indexes WHERE tablename = ? AND indexname = ?",
            String.class,
            table,
            indexName);
    assertThat(defs).as("Expected index %s on table %s to be present", indexName, table).hasSize(1);
    String indexDef = defs.get(0).toLowerCase(Locale.ROOT);
    for (String fragment : fragments) {
      assertThat(indexDef)
          .as("Index %s definition should pin fragment '%s'", indexName, fragment)
          .contains(fragment.toLowerCase(Locale.ROOT));
    }
  }
}
