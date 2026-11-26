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

import de.greluc.krt.profit.basetool.backend.platform.api.PersonalDataExportSection.Section;
import de.greluc.krt.profit.basetool.backend.privacy.internal.ExportSectionDeclarations;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Holds the Art. 15 / Art. 20 export (REQ-SEC-058) to every column of the member's tables and to
 * every table that hangs off them (REQ-DATA-021).
 *
 * <p>A member table is a table an export section filters by {@code :userId}, except the {@link
 * #ROLE_HOLDER_TABLES}, plus every child table that holds a foreign key to a member table and that
 * a section reads. Every column of a member table is read by a section or listed in {@link
 * #COLUMNS_NOT_EXPORTED} with its reason; every child table of a member table is read by a section
 * or listed in {@link #CHILD_TABLES_NOT_EXPORTED}. What a section reads is what PostgreSQL resolves
 * for its statement ({@code information_schema.view_column_usage}), so a column counts when the
 * statement selects it, joins on it or filters by it.
 */
@SpringBootTest
class PersonalDataExportColumnCoverageTest {

  /** Columns that carry no fact: the row id and the optimistic-lock counters. */
  private static final Pattern BOOKKEEPING = Pattern.compile("^(id|version|[a-z_]+_version)$");

  /**
   * The account table. Every table referencing it is a reference to the member, which {@link
   * GdprParticipantCoverageTest} decides column by column, so its children are not swept here.
   */
  private static final String ACCOUNT_TABLE = "app_user";

  private static final String THIRD_PARTY =
      "Names another person who acted on the row, a third party (ADR-0185).";

  private static final String ACCOUNTLESS_NAME =
      "A name typed for a person without an account, which the scrubber cannot see (ADR-0185).";

  private static final String INTERNAL_LINK =
      "An internal id linking another record of the same flow; it identifies nothing to the member"
          + " by itself.";

  private static final String CATALOGUE_LINK =
      "A catalogue id or key whose name the section exports.";

  private static final String UNIT_ID = "The id of an org unit whose name the section exports.";

  private static final String COUNT_OVER_ALL_MEMBERS =
      "A count over every member the run processed, not a fact about the member.";

  private static final String BANK_ACCOUNT_OF_ATTEMPT =
      "The bank account an aUEC attempt books against; the bank sections export the member's own"
          + " bookings and requests with their accounts.";

  private static final String SNAPSHOT_OF_EITHER =
      "A name snapshot that is the member's own name on one side of the row and a third party on"
          + " the other (ADR-0185).";

  /**
   * Tables an export section filters by {@code :userId} that name the member as one role-holder of
   * an organisation's aggregate rather than as the owner of the row; their other columns and their
   * child tables describe the aggregate, not the member.
   */
  static final Map<String, String> ROLE_HOLDER_TABLES =
      Map.of(
          "org_unit",
          "grand_admiral_user_id names the member as the grand admiral of an organisational unit.",
          "mission",
          "owner_id and party_lead_user_id name the member as the organiser or party lead of an"
              + " organisation's mission, whose name, status and schedule the sections export.",
          "job_order",
          "created_by_member_id names the member who created an organisation's order through a"
              + " connected application.");

  /** Columns of member tables no section reads, each with the reason. */
  static final Map<String, String> COLUMNS_NOT_EXPORTED =
      Map.ofEntries(
          Map.entry("app_user.approved_by_id", THIRD_PARTY),
          Map.entry("audit_event.actor_handle", SNAPSHOT_OF_EITHER),
          Map.entry(
              "audit_event.subject_label",
              "The label of what an action was about, which can itself be a person the scrubber"
                  + " cannot see (ADR-0185)."),
          Map.entry(
              "audit_event.subject_id",
              "The id of what an action was about; without its label it identifies nothing."),
          Map.entry(
              "audit_event.details",
              "The structured payload of an action, which can reference other members' records."),
          Map.entry(
              "bank_account_approval_limit.grantee_kind",
              "USER on every row the section selects by grantee_user_id."),
          Map.entry(
              "bank_account_approval_limit.role_code",
              "Set for a grant to a role; the section selects the grants to the member."),
          Map.entry("bank_account_grant.granted_by", THIRD_PARTY),
          Map.entry(
              "bank_account_view_grant.grantee_kind",
              "USER on every row the section selects by grantee_user_id."),
          Map.entry(
              "bank_account_view_grant.role_code",
              "Set for a grant to a role; the section selects the grants to the member."),
          Map.entry("bank_audit_event.actor_handle", SNAPSHOT_OF_EITHER),
          Map.entry("bank_audit_event.details", "See audit_event.details."),
          Map.entry(
              "bank_audit_event.transaction_id",
              "The id of the booking acted on; bankBookingsAsCounterparty exports the bookings"
                  + " that name the member."),
          Map.entry("bank_booking_request.authorisation_id", INTERNAL_LINK),
          Map.entry("bank_booking_request.counterparty_handle", SNAPSHOT_OF_EITHER),
          Map.entry("bank_booking_request.counterparty_org_unit_id", UNIT_ID),
          Map.entry("bank_booking_request.decided_by", THIRD_PARTY),
          Map.entry("bank_booking_request.decider_handle", THIRD_PARTY),
          Map.entry(
              "bank_booking_request.idempotency_key",
              "A connected application's retry token, no fact about the request."),
          Map.entry("bank_booking_request.owner_approval_granted_by", THIRD_PARTY),
          Map.entry("bank_booking_request.owner_approval_granted_by_handle", THIRD_PARTY),
          Map.entry("bank_booking_request.purpose_ref", INTERNAL_LINK),
          Map.entry(
              "bank_booking_request.reject_reason",
              "Free text of a bank employee that can name a person the scrubber cannot see"
                  + " (ADR-0185); the member receives it in the rejection notification."),
          Map.entry("bank_booking_request.requester_handle", SNAPSHOT_OF_EITHER),
          Map.entry("bank_booking_request.resulting_transaction_id", INTERNAL_LINK),
          Map.entry(
              "bank_booking_request.staff_note",
              "The bank staff's internal free text, which can name a person the scrubber cannot"
                  + " see (ADR-0185)."),
          Map.entry(
              "bank_transaction.counterparty_handle",
              "The member's own name snapshot on the bookings that name them; the export names"
                  + " its subject."),
          Map.entry("bank_transaction.counterparty_org_unit_id", UNIT_ID),
          Map.entry("bank_transaction.initiated_by", THIRD_PARTY),
          Map.entry("bank_transaction.staff_note", "See bank_booking_request.staff_note."),
          Map.entry(
              "bank_posting.created_at",
              "Stamped with its booking's shared instant, which the section exports as the"
                  + " booking's created_at (BankPostingWriter)."),
          Map.entry("blueprint_external_alias.output_item_id", CATALOGUE_LINK),
          Map.entry("deletion_request.decided_by_id", THIRD_PARTY),
          Map.entry("exchange_bulk_undo_run.exchange_client_id", INTERNAL_LINK),
          Map.entry("exchange_bulk_undo_run.installation_id", INTERNAL_LINK),
          Map.entry("exchange_bulk_undo_run.members_done", COUNT_OVER_ALL_MEMBERS),
          Map.entry("exchange_bulk_undo_run.members_failed", COUNT_OVER_ALL_MEMBERS),
          Map.entry("exchange_bulk_undo_run.members_total", COUNT_OVER_ALL_MEMBERS),
          Map.entry("exchange_bulk_undo_run.restored", COUNT_OVER_ALL_MEMBERS),
          Map.entry("exchange_bulk_undo_run.skipped", COUNT_OVER_ALL_MEMBERS),
          Map.entry("exchange_bulk_undo_run.requested_by", THIRD_PARTY),
          Map.entry("exchange_bulk_undo_skip.journal_entry_id", INTERNAL_LINK),
          Map.entry("exchange_change.org_entry_id", INTERNAL_LINK),
          Map.entry(
              "exchange_change.tx",
              "The writing database transaction's counter, which only orders the feed."),
          Map.entry(
              "exchange_journal.batch_id",
              "Groups the writes of one client request; no fact beyond the exported rows."),
          Map.entry("exchange_journal.tx", "See exchange_change.tx."),
          Map.entry(
              "inventory_item.quantity_provenance",
              "Maps the row's quantity to the stock rows it came from, which can be other"
                  + " members' rows."),
          Map.entry("job_order_linked_money_attempt.account_holding_id", INTERNAL_LINK),
          Map.entry("job_order_linked_money_attempt.authorisation_id", INTERNAL_LINK),
          Map.entry("job_order_linked_money_attempt.bank_account_id", BANK_ACCOUNT_OF_ATTEMPT),
          Map.entry("job_order_linked_money_attempt.bank_custodian_id", THIRD_PARTY),
          Map.entry(
              "job_order_linked_money_attempt.bank_target_account_id", BANK_ACCOUNT_OF_ATTEMPT),
          Map.entry("job_order_linked_money_attempt.bank_target_custodian_id", THIRD_PARTY),
          Map.entry("job_order_linked_money_attempt.commitment_id", INTERNAL_LINK),
          Map.entry("job_order_linked_money_attempt.counterparty_name", ACCOUNTLESS_NAME),
          Map.entry(
              "job_order_linked_money_attempt.holding_bank_account_id", BANK_ACCOUNT_OF_ATTEMPT),
          Map.entry("job_order_linked_money_attempt.purpose_ref", INTERNAL_LINK),
          Map.entry("job_order_linked_money_attempt.request_id", INTERNAL_LINK),
          Map.entry("job_order_linked_money_attempt.reversal_id", INTERNAL_LINK),
          Map.entry("job_order_linked_money_attempt.selected_accountless_name", ACCOUNTLESS_NAME),
          Map.entry("job_order_linked_money_attempt.selected_ref", INTERNAL_LINK),
          Map.entry("job_order_linked_money_attempt.transaction_id", INTERNAL_LINK),
          Map.entry("job_order_money_correction_entry.commitment_id", INTERNAL_LINK),
          Map.entry(
              "job_order_money_holder.accountless_name",
              "The name of a holder without an account; a former name of the member's own holder"
                  + " is exported in jobOrderMoneyHolderChanges."),
          Map.entry("job_order_money_operation.account_holding_id", INTERNAL_LINK),
          Map.entry("job_order_money_operation.commitment_id", INTERNAL_LINK),
          Map.entry("job_order_money_operation.peer_order_id", INTERNAL_LINK),
          Map.entry("job_order_money_operation.selected_accountless_name", ACCOUNTLESS_NAME),
          Map.entry("job_order_money_recipient.accountless_name", ACCOUNTLESS_NAME),
          Map.entry("job_order_money_recipient.bank_account_id", BANK_ACCOUNT_OF_ATTEMPT),
          Map.entry("material_claim.participation_id", INTERNAL_LINK),
          Map.entry("material_exchange_offer.inventory_item_id", INTERNAL_LINK),
          Map.entry("material_exchange_offer.item_game_item_id", CATALOGUE_LINK),
          Map.entry("material_exchange_offer.item_product_key", CATALOGUE_LINK),
          Map.entry("material_exchange_request.item_game_item_id", CATALOGUE_LINK),
          Map.entry("material_exchange_request.item_product_key", CATALOGUE_LINK),
          Map.entry(
              "mission_participant.guest_name",
              "Set for a guest without an account, never on the member's own participation."),
          Map.entry(
              "mission_unit.note",
              "The organisers' text about the unit, which can name a person the scrubber cannot"
                  + " see (ADR-0185)."),
          Map.entry(
              "mission_unit.ship_id", "The ship assigned to the unit, possibly another member's."),
          Map.entry("notification.entity_id", INTERNAL_LINK),
          Map.entry("notification.org_unit_id", UNIT_ID),
          Map.entry("operation_payout_status.paid_out_by_user_id", THIRD_PARTY),
          Map.entry("personal_blueprint.output_item_id", CATALOGUE_LINK),
          Map.entry("personal_blueprint.product_key", CATALOGUE_LINK),
          Map.entry(
              "personal_inventory_item.location_id",
              "A catalogue link whose name snapshot the section exports."),
          Map.entry("purchasing_deal.mission_id", INTERNAL_LINK),
          Map.entry("purchasing_deal.need_id", INTERNAL_LINK),
          Map.entry(
              "purchasing_deal.partner_id",
              "The deal's trade partner, a third party (REQ-PUR-028)."),
          Map.entry("purchasing_deal_delivery.accepted_by", THIRD_PARTY),
          Map.entry("purchasing_deal_delivery.booked_row_id", INTERNAL_LINK),
          Map.entry("purchasing_deal_settlement.remainder_request_id", INTERNAL_LINK),
          Map.entry("purchasing_deal_tranche.bank_request_id", INTERNAL_LINK),
          Map.entry("purchasing_deal_tranche.transaction_id", INTERNAL_LINK),
          Map.entry("purchasing_delegate.appointed_by", THIRD_PARTY),
          Map.entry("purchasing_delegate.revoked_by", THIRD_PARTY),
          Map.entry(
              "supply_donation.decline_reason",
              "Free text of the receiving unit that can name a person the scrubber cannot see"
                  + " (ADR-0185)."),
          Map.entry("supply_donation.need_id", INTERNAL_LINK),
          Map.entry("supply_donation.received_by", THIRD_PARTY),
          Map.entry("supply_donation.source_inventory_item_id", INTERNAL_LINK),
          Map.entry("user_approval_event.decided_by_id", THIRD_PARTY));

  /** Child tables of member tables no section reads, each with the reason. */
  static final Map<String, String> CHILD_TABLES_NOT_EXPORTED =
      Map.of(
          "bank_linked_attempt",
          "The bank's side of an aUEC order's linked attempt; the member's part is exported in"
              + " jobOrderLinkedMoneyAttempts.",
          "job_order_money_commitment_change",
          "Changes an operation made to a unit's outstanding commitment; they describe the unit's"
              + " balance, not the member.");

  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("every column of a member table is exported or listed as not exported")
  void everyColumnOfAMemberTableIsExportedOrListed() throws SQLException {
    Coverage coverage = declaredCoverage();

    assertThat(coverage.memberTables())
        .as("member tables (an emptied registry would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(40);
    assertThat(coverage.columnGaps())
        .as(
            """
            Columns of the member's tables the Art. 15 export (REQ-SEC-058) neither reads nor \
            excludes. Select the column in the owning module's PersonalDataExportSection, or list \
            it in COLUMNS_NOT_EXPORTED with the reason it is not about the member.\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("every child table of a member table is exported or listed as not exported")
  void everyChildTableOfAMemberTableIsExportedOrListed() throws SQLException {
    Coverage coverage = declaredCoverage();

    assertThat(coverage.childGaps())
        .as(
            """
            Tables that hang off the member's tables by a foreign key and that no export section \
            reads. Add a section to the owning module's PersonalDataExportSection, or list the \
            table in CHILD_TABLES_NOT_EXPORTED with the reason its rows are not about the member.\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("every listed exclusion still names an unread column or table of the sweep")
  void everyListedExclusionIsStillNeeded() throws SQLException {
    assertThat(declaredCoverage().staleEntries())
        .as(
            """
            Exclusions that no longer hold: the column or table is gone, a section reads it now, \
            or it is no longer part of the member's tables. Remove the entry.\
            """)
        .isEmpty();
  }

  @Test
  @DisplayName("proof: a planted column on a member table and a planted child table are reported")
  void aPlantedColumnAndAPlantedChildTableAreReported() throws SQLException {
    Coverage coverage =
        RolledBackTransaction.run(
            dataSource,
            jdbc -> {
              jdbc.execute("ALTER TABLE ship ADD COLUMN planted_fact VARCHAR(20)");
              jdbc.execute(
                  "CREATE TABLE planted_ship_note (id UUID PRIMARY KEY,"
                      + " ship_id UUID REFERENCES ship (id))");
              return coverage(jdbc, ExportSectionDeclarations.inReadingOrder());
            });

    assertThat(coverage.columnGaps()).contains("ship.planted_fact");
    assertThat(coverage.childGaps()).contains("planted_ship_note (child of ship)");
  }

  /**
   * The result of one sweep.
   *
   * @param memberTables the member tables, child tables a section reads included
   * @param columnGaps the member-table columns neither read nor listed
   * @param childGaps the child tables neither read nor listed
   * @param staleEntries the listed exclusions the sweep no longer needs
   */
  record Coverage(
      Set<String> memberTables,
      Set<String> columnGaps,
      Set<String> childGaps,
      Set<String> staleEntries) {}

  private Coverage declaredCoverage() throws SQLException {
    return RolledBackTransaction.run(
        dataSource, jdbc -> coverage(jdbc, ExportSectionDeclarations.inReadingOrder()));
  }

  /**
   * Sweeps the schema against the sections.
   *
   * @param jdbc a template bound to a transaction that is rolled back
   * @param sections the sections to hold the schema to
   * @return the sweep's result
   */
  private static @NotNull Coverage coverage(
      @NotNull JdbcTemplate jdbc, @NotNull List<Section> sections) {
    SchemaCatalog schema = SchemaCatalog.read(jdbc);
    Set<String> read = readColumns(jdbc, sections);
    Set<String> readTables = new TreeSet<>();
    read.forEach(column -> readTables.add(tableOf(column)));

    Set<String> keyTables = new TreeSet<>();
    for (Section section : sections) {
      GdprParticipantCoverageTest.exportKeys(section.sql(), schema.tables())
          .forEach(key -> keyTables.add(tableOf(key)));
    }
    Map<String, Set<String>> children = childTables(jdbc);

    Set<String> memberTables = new TreeSet<>(keyTables);
    memberTables.removeAll(ROLE_HOLDER_TABLES.keySet());
    Set<String> unreadChildren = new TreeSet<>();
    Set<String> childGaps = new TreeSet<>();
    boolean grew = true;
    while (grew) {
      grew = false;
      for (String parent : Set.copyOf(memberTables)) {
        if (parent.equals(ACCOUNT_TABLE)) {
          continue;
        }
        for (String child : children.getOrDefault(parent, Set.of())) {
          if (keyTables.contains(child) || memberTables.contains(child)) {
            continue;
          }
          if (readTables.contains(child)) {
            grew |= memberTables.add(child);
          } else {
            unreadChildren.add(child);
            if (!CHILD_TABLES_NOT_EXPORTED.containsKey(child)) {
              childGaps.add(child + " (child of " + parent + ")");
            }
          }
        }
      }
    }

    Set<String> columnGaps = new TreeSet<>();
    Set<String> unreadColumns = new TreeSet<>();
    for (String column : schema.columns()) {
      String name = column.substring(column.indexOf('.') + 1);
      if (memberTables.contains(tableOf(column))
          && !BOOKKEEPING.matcher(name).matches()
          && !read.contains(column)) {
        unreadColumns.add(column);
        if (!COLUMNS_NOT_EXPORTED.containsKey(column)) {
          columnGaps.add(column);
        }
      }
    }

    Set<String> stale = new TreeSet<>();
    COLUMNS_NOT_EXPORTED.keySet().stream()
        .filter(column -> !unreadColumns.contains(column))
        .forEach(column -> stale.add("COLUMNS_NOT_EXPORTED " + column));
    CHILD_TABLES_NOT_EXPORTED.keySet().stream()
        .filter(table -> !unreadChildren.contains(table))
        .forEach(table -> stale.add("CHILD_TABLES_NOT_EXPORTED " + table));
    ROLE_HOLDER_TABLES.keySet().stream()
        .filter(table -> !keyTables.contains(table))
        .forEach(table -> stale.add("ROLE_HOLDER_TABLES " + table));
    return new Coverage(memberTables, columnGaps, childGaps, stale);
  }

  private static @NotNull String tableOf(@NotNull String qualifiedColumn) {
    return qualifiedColumn.substring(0, qualifiedColumn.indexOf('.'));
  }

  /**
   * Resolves every column each section's statement reads, through a temporary view per statement.
   *
   * @param jdbc a template bound to a transaction that is rolled back
   * @param sections the sections to resolve
   * @return every {@code table.column} some statement reads
   */
  private static @NotNull Set<String> readColumns(
      @NotNull JdbcTemplate jdbc, @NotNull List<Section> sections) {
    Set<String> read = new TreeSet<>();
    for (Section section : sections) {
      jdbc.execute(
          "CREATE TEMP VIEW export_probe AS "
              + section.sql().replace(":userId", "CAST(NULL AS uuid)"));
      read.addAll(
          jdbc.queryForList(
              """
              SELECT table_name || '.' || column_name FROM information_schema.view_column_usage
              WHERE view_name = 'export_probe'
              """,
              String.class));
      jdbc.execute("DROP VIEW export_probe");
    }
    return read;
  }

  /**
   * Maps every table to the tables holding a foreign key to it.
   *
   * @param jdbc the template to query with
   * @return each referenced table mapped to its referencing tables, self-references left out
   */
  private static @NotNull Map<String, Set<String>> childTables(@NotNull JdbcTemplate jdbc) {
    Map<String, Set<String>> children = new TreeMap<>();
    jdbc.query(
        """
        SELECT DISTINCT tgt.relname, src.relname
        FROM pg_constraint con
        JOIN pg_class src ON src.oid = con.conrelid
        JOIN pg_class tgt ON tgt.oid = con.confrelid
        JOIN pg_namespace n ON n.oid = src.relnamespace
        WHERE con.contype = 'f' AND n.nspname = current_schema() AND src.oid <> tgt.oid
        """,
        rs -> {
          children.computeIfAbsent(rs.getString(1), _ -> new TreeSet<>()).add(rs.getString(2));
        });
    return children;
  }
}
