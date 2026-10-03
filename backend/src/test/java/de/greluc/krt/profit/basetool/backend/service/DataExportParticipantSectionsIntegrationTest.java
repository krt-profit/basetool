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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.DataExportSections;
import de.greluc.krt.profit.basetool.backend.support.HandleScrubber;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the export sections over rows that name the member in a role somebody else
 * gave them: realm roles, a grand-admiral appointment, a party lead, a unit responsibility, an
 * interest in a market request, bank booking rights, approval limits and booking requests with the
 * member as counterparty (REQ-SEC-058, REQ-DATA-021).
 *
 * <p>Each test seeds the member's row next to the same kind of row for another member, with that
 * other member's handle in every name and free-text column, and asserts that the section returns
 * the member's row only, with the handle scrubbed or not selected.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DataExportParticipantSectionsIntegrationTest {

  /** Long and distinctive enough that the scrubber matches it and a hit is no coincidence. */
  private static final String OTHER_HANDLE = "ZzzThirdPartyHandleZzz";

  /** A name no scrubber knows, so only an unselected column keeps it out of the export. */
  private static final String EXTERNAL_CONTACT = "ZzzUnregisteredContactZzz";

  @Autowired private DataExportService dataExportService;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  private UUID subject;
  private UUID other;

  /** Seeds the subject and the third party whose handle must never reach the subject's export. */
  @BeforeEach
  void seedMembers() {
    subject = user("ZzzExportSubjectZzz");
    other = user(OTHER_HANDLE);
  }

  private @NotNull UUID user(@NotNull String username) {
    User u = new User();
    u.setId(UUID.randomUUID());
    u.setUsername(username);
    return userRepository.saveAndFlush(u).getId();
  }

  private @NotNull List<Map<String, Object>> rows(@NotNull String key) {
    DataExportService.DataExport export = dataExportService.export(subject);
    assertThat(
            export.sections().stream()
                .flatMap(s -> s.rows().stream())
                .flatMap(r -> r.values().stream())
                .filter(String.class::isInstance)
                .map(String.class::cast))
        .as("no third party's handle or external contact anywhere in the export")
        .noneMatch(v -> v.contains(OTHER_HANDLE) || v.contains(EXTERNAL_CONTACT));
    return export.sections().stream()
        .filter(s -> s.key().equals(key))
        .findFirst()
        .orElseThrow()
        .rows();
  }

  private static @NotNull String legalBasis(@NotNull String key) {
    return DataExportSections.SECTIONS.stream()
        .filter(s -> s.key().equals(key))
        .findFirst()
        .orElseThrow()
        .legalBasis();
  }

  private @NotNull UUID mission(@NotNull String name, @Nullable UUID partyLead) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO mission (id, name, version, party_lead_user_id) VALUES (?, ?, 0, ?)",
        id,
        name,
        partyLead);
    return id;
  }

  private @NotNull UUID bankAccount(@NotNull String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_account (id, account_no, name, type) VALUES (?, ?, ?, 'SPECIAL')",
        id,
        "T" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999),
        name);
    return id;
  }

  private @NotNull UUID orgUnitWithGrandAdmiral(@NotNull String name, @NotNull UUID admiral) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible, grand_admiral_user_id)"
            + " VALUES (?, 'ORGANISATIONSLEITUNG', ?, ?, TRUE, FALSE, FALSE, ?)",
        id,
        name,
        "G" + id.toString().substring(0, 6),
        admiral);
    return id;
  }

  private @NotNull UUID bookingRequest(
      @NotNull UUID account, @NotNull UUID requester, @NotNull UUID counterparty) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_booking_request (id, account_id, type, amount, note, justification,"
            + " requested_by, requester_handle, counterparty_user_id, counterparty_handle,"
            + " staff_note, reject_reason)"
            + " VALUES (?, ?, 'DEPOSIT', 1500, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        account,
        "Einzahlung von " + OTHER_HANDLE,
        "Abgesprochen mit " + OTHER_HANDLE,
        requester,
        OTHER_HANDLE,
        counterparty,
        "ZzzExportSubjectZzz",
        "Intern: " + EXTERNAL_CONTACT,
        "Abgelehnt wegen " + EXTERNAL_CONTACT);
    return id;
  }

  @Test
  void realmRolesListTheMembersOwnRoles() {
    Long role =
        jdbc.queryForObject(
            "INSERT INTO role (code, name, description, version)"
                + " VALUES ('ZZZ_EXPORT_ROLE', 'Zzz Export Role', ?, 0) RETURNING id",
            Long.class,
            "Vergeben an " + EXTERNAL_CONTACT);
    Long othersRole =
        jdbc.queryForObject(
            "INSERT INTO role (code, name, version)"
                + " VALUES ('ZZZ_OTHER_ROLE', 'Zzz Other Role', 0) RETURNING id",
            Long.class);
    jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", subject, role);
    jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", other, othersRole);

    List<Map<String, Object>> rows = rows("realmRoles");

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("code", "ZZZ_EXPORT_ROLE")
        .containsEntry("role", "Zzz Export Role")
        .doesNotContainKey("description");
    assertThat(legalBasis("realmRoles")).isEqualTo(DataExportSections.ART_15);
  }

  @Test
  void aGrandAdmiralAppointmentNamesTheUnit() {
    orgUnitWithGrandAdmiral("Zzz Leitung Eins", subject);
    orgUnitWithGrandAdmiral("Zzz Leitung Zwei", other);

    List<Map<String, Object>> rows = rows("orgUnitsAsGrandAdmiral");

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("org_unit", "Zzz Leitung Eins")
        .containsEntry("kind", "ORGANISATIONSLEITUNG")
        .containsKey("shorthand")
        .doesNotContainKey("grand_admiral_display_name");
  }

  @Test
  void aPartyLeadDesignationNamesTheMissionScrubbed() {
    mission("Bergung mit " + OTHER_HANDLE, subject);
    mission("Fremder Einsatz", other);

    List<Map<String, Object>> rows = rows("missionsAsPartyLead");

    assertThat(rows).hasSize(1);
    assertThat(String.valueOf(rows.get(0).get("mission")))
        .startsWith("Bergung mit ")
        .contains(HandleScrubber.REPLACEMENT);
    assertThat(rows.get(0)).containsKeys("status", "planned_start_time", "planned_end_time");
  }

  @Test
  void aUnitResponsibilityNamesMissionAndUnitButNotTheNoteOrShip() {
    UUID mission = mission("Konvoi " + OTHER_HANDLE, null);
    jdbc.update(
        "INSERT INTO mission_unit (id, mission_id, name, high_value_unit, version,"
            + " responsible_user_id, note) VALUES (?, ?, ?, TRUE, 0, ?, ?)",
        UUID.randomUUID(),
        mission,
        "Hornet von " + OTHER_HANDLE,
        subject,
        "Absprache mit " + EXTERNAL_CONTACT);
    jdbc.update(
        "INSERT INTO mission_unit (id, mission_id, name, high_value_unit, version,"
            + " responsible_user_id) VALUES (?, ?, 'Fremde Einheit', FALSE, 0, ?)",
        UUID.randomUUID(),
        mission,
        other);

    List<Map<String, Object>> rows = rows("missionUnitResponsibilities");

    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    assertThat(String.valueOf(row.get("unit")))
        .startsWith("Hornet von ")
        .contains(HandleScrubber.REPLACEMENT);
    assertThat(String.valueOf(row.get("mission"))).contains(HandleScrubber.REPLACEMENT);
    assertThat(row).containsEntry("high_value_unit", true).doesNotContainKeys("note", "ship_id");
  }

  @Test
  void anInterestInAMarketRequestDoesNotNameTheRequester() {
    UUID request = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material_exchange_request (id, request_kind, item_product_key, item_name,"
            + " item_quantity, owner_id, remark, status, posted_at, version, created_at,"
            + " updated_at) VALUES (?, 'ITEM', 'zzz-key', 'Zzz Probe-Item', 2, ?, ?, 'ACTIVE',"
            + " now(), 0, now(), now())",
        request,
        other,
        "Bitte bei " + OTHER_HANDLE + " melden");
    jdbc.update(
        "INSERT INTO material_exchange_request_interest (id, request_id, interested_user_id,"
            + " version, created_at, updated_at) VALUES (?, ?, ?, 0, now(), now())",
        UUID.randomUUID(),
        request,
        subject);

    List<Map<String, Object>> rows = rows("marketRequestInterests");

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("request_kind", "ITEM")
        .containsEntry("item_name", "Zzz Probe-Item")
        .doesNotContainKeys("remark", "owner_id");
    assertThat(legalBasis("marketRequestInterests")).isEqualTo(DataExportSections.ART_15_20);
  }

  @Test
  void bookingGrantsListTheRightsButNotTheGrantingManager() {
    UUID account = bankAccount("Kasse " + OTHER_HANDLE);
    jdbc.update(
        "INSERT INTO bank_account_grant (user_id, account_id, can_deposit, can_withdraw,"
            + " can_transfer, granted_by) VALUES (?, ?, TRUE, FALSE, TRUE, ?)",
        subject,
        account,
        other);
    jdbc.update(
        "INSERT INTO bank_account_grant (user_id, account_id, can_deposit) VALUES (?, ?, TRUE)",
        other,
        account);

    List<Map<String, Object>> rows = rows("bankAccountBookingGrants");

    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    assertThat(row)
        .containsEntry("can_deposit", true)
        .containsEntry("can_withdraw", false)
        .containsEntry("can_transfer", true)
        .doesNotContainKey("granted_by");
    assertThat(String.valueOf(row.get("account"))).contains(HandleScrubber.REPLACEMENT);
  }

  @Test
  void approvalLimitsListTheMembersOwnCeiling() {
    UUID account = bankAccount("Zzz Limitkonto");
    jdbc.update(
        "INSERT INTO bank_account_approval_limit (id, account_id, grantee_kind, grantee_user_id,"
            + " limit_amount) VALUES (?, ?, 'USER', ?, 5000)",
        UUID.randomUUID(),
        account,
        subject);
    jdbc.update(
        "INSERT INTO bank_account_approval_limit (id, account_id, grantee_kind, grantee_user_id,"
            + " limit_amount) VALUES (?, ?, 'USER', ?, 9000)",
        UUID.randomUUID(),
        account,
        other);

    List<Map<String, Object>> rows = rows("bankApprovalLimits");

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("account", "Zzz Limitkonto").containsKey("account_no");
    assertThat((BigDecimal) rows.get(0).get("limit_amount"))
        .isEqualByComparingTo(BigDecimal.valueOf(5000));
  }

  @Test
  void aRequestNamingTheMemberAsCounterpartyHidesTheRequesterAndScrubsTheirText() {
    UUID account = bankAccount("Zzz Anfragekonto");
    bookingRequest(account, other, subject);
    bookingRequest(account, subject, other);

    List<Map<String, Object>> rows = rows("bankRequestsAsCounterparty");

    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    assertThat(row)
        .containsEntry("type", "DEPOSIT")
        .containsEntry("status", "PENDING")
        .doesNotContainKeys(
            "requested_by",
            "requester_handle",
            "decided_by",
            "decider_handle",
            "staff_note",
            "reject_reason");
    assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(BigDecimal.valueOf(1500));
    assertThat(String.valueOf(row.get("note")))
        .startsWith("Einzahlung von ")
        .contains(HandleScrubber.REPLACEMENT);
    assertThat(String.valueOf(row.get("justification"))).contains(HandleScrubber.REPLACEMENT);
    assertThat(rows("bankRequestsRaised"))
        .as("the request the member raised is in its own section, not this one")
        .hasSize(1);
  }
}
