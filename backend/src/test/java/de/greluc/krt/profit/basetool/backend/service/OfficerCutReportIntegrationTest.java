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

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pre-cut report {@code scripts/officer-cut-report.sql} against Postgres (REQ-SEC-004,
 * REQ-ORG-029): it opens a read-only session, lists every leadership seat holder without {@code KRT
 * Member} and every row the {@code OFFICER} cut removes, and prints a count line per section so an
 * empty section reads {@code count=0}. Runs in a rolled-back transaction; the session-wide
 * read-only statement is asserted, not executed, so it cannot leak into the pooled connection.
 */
@SpringBootTest
@Transactional
class OfficerCutReportIntegrationTest {

  private static final Path SCRIPT = Path.of("../scripts/officer-cut-report.sql");
  private static final String READ_ONLY = "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY";
  private static final Pattern STATEMENT_END = Pattern.compile(";\\s*\\R");

  private static final UUID SEAT_WITHOUT_ROLE =
      UUID.fromString("44444444-4444-4444-4444-4444444928a1");
  private static final UUID SEAT_WITH_ROLE =
      UUID.fromString("44444444-4444-4444-4444-4444444928a2");
  private static final UUID PLAIN_MEMBER = UUID.fromString("44444444-4444-4444-4444-4444444928a3");
  private static final UUID OFFICER_WITHOUT_SEAT =
      UUID.fromString("44444444-4444-4444-4444-4444444928a4");
  private static final UUID SPECIAL_ACCOUNT =
      UUID.fromString("44444444-4444-4444-4444-4444444928b1");
  private static final UUID VIEW_GRANT = UUID.fromString("44444444-4444-4444-4444-4444444928b2");
  private static final UUID APPROVAL_LIMIT =
      UUID.fromString("44444444-4444-4444-4444-4444444928b3");
  private static final UUID RULE = UUID.fromString("44444444-4444-4444-4444-4444444928c1");
  private static final UUID SELECTOR = UUID.fromString("44444444-4444-4444-4444-4444444928c2");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;

  private Squadron squadron;

  @BeforeEach
  void seed() {
    Role krtMember = roleRepository.findByNameIgnoreCase("KRT Member").orElseThrow();
    Role officer = new Role();
    officer.setCode("OFFICER");
    officer.setName("Officer");
    officer = roleRepository.saveAndFlush(officer);
    user(SEAT_WITHOUT_ROLE, "cut-seat-without", Set.of(officer));
    user(SEAT_WITH_ROLE, "cut-seat-with", Set.of(krtMember, officer));
    user(PLAIN_MEMBER, "cut-plain", Set.of());
    user(OFFICER_WITHOUT_SEAT, "cut-officer", Set.of(krtMember, officer));
    squadron = new Squadron();
    squadron.setName("Officer Cut " + UUID.randomUUID());
    squadron.setShorthand("OC" + UUID.randomUUID().toString().substring(0, 6));
    squadron = squadronRepository.saveAndFlush(squadron);
    membership(SEAT_WITHOUT_ROLE, MembershipRole.STAFFELLEITER);
    membership(SEAT_WITH_ROLE, MembershipRole.ENSIGN);
    membership(PLAIN_MEMBER, MembershipRole.MEMBER);
    membership(OFFICER_WITHOUT_SEAT, MembershipRole.MEMBER);
    jdbcTemplate.update(
        "INSERT INTO bank_account (id, account_no, name, type) VALUES (?, ?, ?, 'SPECIAL')",
        SPECIAL_ACCOUNT,
        "KB-CUT-928",
        "Officer cut special");
    jdbcTemplate.update(
        "INSERT INTO bank_account_view_grant (id, account_id, grantee_kind, role_code)"
            + " VALUES (?, ?, 'GLOBAL_ROLE', 'OFFICER')",
        VIEW_GRANT,
        SPECIAL_ACCOUNT);
    jdbcTemplate.update(
        "INSERT INTO bank_account_approval_limit"
            + " (id, account_id, grantee_kind, role_code, limit_amount)"
            + " VALUES (?, ?, 'GLOBAL_ROLE', 'OFFICER', 1000)",
        APPROVAL_LIMIT,
        SPECIAL_ACCOUNT);
    jdbcTemplate.update(
        "INSERT INTO notification_rule (id, event_type, notification_type, enabled, exclude_actor)"
            + " VALUES (?, 'JOB_ORDER_CREATED', 'JOB_ORDER_CREATED', TRUE, TRUE)",
        RULE);
    jdbcTemplate.update(
        "INSERT INTO notification_rule_selector (id, rule_id, kind, role_code)"
            + " VALUES (?, ?, 'ROLE', 'OFFICER')",
        SELECTOR,
        RULE);
  }

  private void user(UUID id, String name, Set<Role> roles) {
    User user = new User();
    user.setId(id);
    user.setUsername(name);
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(roles));
    userRepository.saveAndFlush(user);
  }

  private void membership(UUID userId, MembershipRole role) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(userId, squadron.getId()));
    membership.setUser(userRepository.findById(userId).orElseThrow());
    membership.setKind(OrgUnitKind.SQUADRON);
    membership.setRole(role);
    membership.setJoinedAt(Instant.now());
    membershipRepository.saveAndFlush(membership);
  }

  private static List<String> statements() throws IOException {
    return Arrays.stream(STATEMENT_END.split(Files.readString(SCRIPT, StandardCharsets.UTF_8)))
        .map(String::strip)
        .filter(statement -> !statement.isEmpty())
        .toList();
  }

  private List<String> report() throws IOException {
    return statements().stream()
        .filter(statement -> !statement.startsWith("SET "))
        .map(OfficerCutReportIntegrationTest::query)
        .flatMap(query -> jdbcTemplate.queryForList(query, String.class).stream())
        .toList();
  }

  private static String query(String statement) {
    return statement.lines().filter(line -> !line.startsWith("\\")).collect(joining("\n")).strip();
  }

  @Test
  void theReportOpensAReadOnlySessionAndRunsOnlySelects() throws IOException {
    List<String> statements = statements();

    assertThat(statements.getFirst()).isEqualTo(READ_ONLY);
    assertThat(
            statements.subList(1, statements.size()).stream()
                .map(OfficerCutReportIntegrationTest::query))
        .allMatch(query -> query.startsWith("WITH ") || query.startsWith("SELECT "));
  }

  @Test
  void everySectionPrintsItsCount() throws IOException {
    assertThat(report())
        .anyMatch(line -> line.startsWith("seat_without_krt_member|count="))
        .anyMatch(line -> line.startsWith("officer_without_seat|count="))
        .anyMatch(line -> line.startsWith("bank_view_grant_officer|count="))
        .anyMatch(line -> line.startsWith("bank_approval_limit_officer|count="))
        .anyMatch(line -> line.startsWith("notification_rule_role_officer|count="));
  }

  @Test
  void onlySeatHoldersWithoutKrtMemberAreListedAsBlockers() throws IOException {
    List<String> seatLines =
        report().stream().filter(line -> line.startsWith("seat_without_krt_member|user=")).toList();

    assertThat(seatLines)
        .contains(
            "seat_without_krt_member|user="
                + SEAT_WITHOUT_ROLE
                + "|unit="
                + squadron.getId()
                + "|kind=SQUADRON|rank=STAFFELLEITER|in_keycloak=true|enabled=true");
    assertThat(String.join("\n", seatLines))
        .doesNotContain(SEAT_WITH_ROLE.toString())
        .doesNotContain(PLAIN_MEMBER.toString())
        .doesNotContain(OFFICER_WITHOUT_SEAT.toString())
        .doesNotContain("cut-");
  }

  @Test
  void officersWithoutSeatAreListed() throws IOException {
    assertThat(report())
        .contains("officer_without_seat|user=" + OFFICER_WITHOUT_SEAT)
        .doesNotContain("officer_without_seat|user=" + SEAT_WITH_ROLE);
  }

  @Test
  void theOfficerBankBucketsAndRoleRulesAreListed() throws IOException {
    assertThat(report())
        .contains(
            "bank_view_grant_officer|grant="
                + VIEW_GRANT
                + "|account="
                + SPECIAL_ACCOUNT
                + "|type=SPECIAL",
            "bank_approval_limit_officer|limit="
                + APPROVAL_LIMIT
                + "|account="
                + SPECIAL_ACCOUNT
                + "|type=SPECIAL",
            "notification_rule_role_officer|selector="
                + SELECTOR
                + "|rule="
                + RULE
                + "|event=JOB_ORDER_CREATED|enabled=true");
  }
}
