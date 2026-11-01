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

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The data cut of the {@code OFFICER} retirement (ADR-0300, REQ-SEC-004, REQ-BANK-037): the SQL of
 * migrations {@code V317}, {@code V318} and {@code V319} is run against rows the earlier schema
 * could hold, in a rolled-back transaction.
 */
@SpringBootTest
@Transactional
class OfficerCutMigrationTest {

  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
  private static final UUID OFFICER_USER = UUID.fromString("55555555-5555-5555-5555-5555555928a1");
  private static final UUID ACCOUNT = UUID.fromString("55555555-5555-5555-5555-5555555928b1");
  private static final UUID OFFICER_GRANT = UUID.fromString("55555555-5555-5555-5555-5555555928b2");
  private static final UUID LOGISTICIAN_GRANT =
      UUID.fromString("55555555-5555-5555-5555-5555555928b3");
  private static final UUID OFFICER_LIMIT = UUID.fromString("55555555-5555-5555-5555-5555555928b4");
  private static final UUID LOGISTICIAN_LIMIT =
      UUID.fromString("55555555-5555-5555-5555-5555555928b5");
  private static final UUID RULE = UUID.fromString("55555555-5555-5555-5555-5555555928c1");
  private static final UUID OFFICER_SELECTOR =
      UUID.fromString("55555555-5555-5555-5555-5555555928c2");
  private static final UUID ADMIN_SELECTOR =
      UUID.fromString("55555555-5555-5555-5555-5555555928c3");
  private static final UUID RELATIVE_SELECTOR =
      UUID.fromString("55555555-5555-5555-5555-5555555928c4");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;

  @BeforeEach
  void seed() {
    Role officer = new Role();
    officer.setCode("OFFICER");
    officer.setName("Officer");
    officer.setPermissions(new HashSet<>(Set.of("MISSION_MANAGE")));
    officer = roleRepository.saveAndFlush(officer);
    User user = new User();
    user.setId(OFFICER_USER);
    user.setUsername("cut-officer");
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(officer)));
    userRepository.saveAndFlush(user);
    jdbcTemplate.update(
        "INSERT INTO bank_account (id, account_no, name, type) VALUES (?, ?, ?, 'SPECIAL')",
        ACCOUNT,
        "KB-CUT-929",
        "Officer cut special");
    grant(OFFICER_GRANT, "OFFICER");
    grant(LOGISTICIAN_GRANT, "LOGISTICIAN");
    limit(OFFICER_LIMIT, "OFFICER");
    limit(LOGISTICIAN_LIMIT, "LOGISTICIAN");
    jdbcTemplate.update(
        "INSERT INTO notification_rule (id, event_type, notification_type, enabled, exclude_actor)"
            + " VALUES (?, 'JOB_ORDER_CREATED', 'JOB_ORDER_CREATED', TRUE, TRUE)",
        RULE);
    jdbcTemplate.update(
        "INSERT INTO notification_rule_selector (id, rule_id, kind, role_code)"
            + " VALUES (?, ?, 'ROLE', 'OFFICER'), (?, ?, 'ROLE', 'ADMIN')",
        OFFICER_SELECTOR,
        RULE,
        ADMIN_SELECTOR,
        RULE);
    jdbcTemplate.update(
        "INSERT INTO notification_rule_selector"
            + " (id, rule_id, kind, org_relative_role, context_role)"
            + " VALUES (?, ?, 'ORG_RELATIVE_ROLE', 'OFFICER', 'RESPONSIBLE')",
        RELATIVE_SELECTOR,
        RULE);
  }

  private void grant(UUID id, String roleCode) {
    jdbcTemplate.update(
        "INSERT INTO bank_account_view_grant (id, account_id, grantee_kind, role_code)"
            + " VALUES (?, ?, 'GLOBAL_ROLE', ?)",
        id,
        ACCOUNT,
        roleCode);
  }

  private void limit(UUID id, String roleCode) {
    jdbcTemplate.update(
        "INSERT INTO bank_account_approval_limit"
            + " (id, account_id, grantee_kind, role_code, limit_amount)"
            + " VALUES (?, ?, 'GLOBAL_ROLE', ?, 1000)",
        id,
        ACCOUNT,
        roleCode);
  }

  private void migrate(String name) throws IOException {
    try (var files = Files.list(MIGRATIONS)) {
      Path script =
          files
              .filter(file -> file.getFileName().toString().startsWith(name + "__"))
              .findFirst()
              .orElseThrow();
      jdbcTemplate.execute(Files.readString(script, StandardCharsets.UTF_8));
    }
  }

  private int count(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  @Test
  void theOfficerRoleLeavesWithItsAssignmentsAndPermissions() throws IOException {
    long officerRoleId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM role WHERE upper(code) = 'OFFICER'", Long.class);

    migrate("V317");

    assertThat(count("SELECT count(*) FROM role WHERE upper(code) = 'OFFICER'")).isZero();
    assertThat(count("SELECT count(*) FROM user_roles WHERE user_id = ?", OFFICER_USER)).isZero();
    assertThat(count("SELECT count(*) FROM role_permissions WHERE role_id = ?", officerRoleId))
        .isZero();
    assertThat(count("SELECT count(*) FROM role WHERE code = 'ADMIN'")).isEqualTo(1);
  }

  @Test
  void theOfficerBankBucketsAreDeletedWithOneSystemAuditRowEach() throws IOException {
    migrate("V318");

    assertThat(count("SELECT count(*) FROM bank_account_view_grant WHERE id = ?", OFFICER_GRANT))
        .isZero();
    assertThat(
            count("SELECT count(*) FROM bank_account_view_grant WHERE id = ?", LOGISTICIAN_GRANT))
        .isEqualTo(1);
    assertThat(
            count("SELECT count(*) FROM bank_account_approval_limit WHERE id = ?", OFFICER_LIMIT))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM bank_account_approval_limit WHERE id = ?", LOGISTICIAN_LIMIT))
        .isEqualTo(1);
    List<Map<String, Object>> audit =
        jdbcTemplate.queryForList(
            "SELECT event_type, actor_handle, details, actor_user_id FROM bank_audit_event"
                + " WHERE account_id = ? ORDER BY event_type",
            ACCOUNT);
    assertThat(audit)
        .extracting(row -> row.get("event_type"))
        .containsExactly("APPROVAL_LIMIT_CLEARED", "BALANCE_VISIBILITY_REVOKED");
    assertThat(audit)
        .allSatisfy(row -> {
          assertThat(row.get("actor_handle")).isEqualTo("system");
          assertThat(row.get("details")).isEqualTo("GLOBAL_ROLE:OFFICER");
          assertThat(row.get("actor_user_id")).isNull();
        });
  }

  @Test
  void theRoleOfficerSelectorsBecomeSeatHolderSelectors() throws IOException {
    migrate("V319");

    Map<String, Object> moved =
        jdbcTemplate.queryForMap(
            "SELECT kind, role_code FROM notification_rule_selector WHERE id = ?",
            OFFICER_SELECTOR);
    assertThat(moved.get("kind")).isEqualTo("SEAT_HOLDERS");
    assertThat(moved.get("role_code")).isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT kind FROM notification_rule_selector WHERE id = ?",
                String.class,
                ADMIN_SELECTOR))
        .isEqualTo("ROLE");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT org_relative_role FROM notification_rule_selector WHERE id = ?",
                String.class,
                RELATIVE_SELECTOR))
        .isEqualTo("OFFICER");
  }

  @Test
  void aRuleThatAlreadyHasSeatHoldersDoesNotGetThemTwice() throws IOException {
    jdbcTemplate.update(
        "INSERT INTO notification_rule_selector (id, rule_id, kind) VALUES (?, ?, 'SEAT_HOLDERS')",
        UUID.randomUUID(),
        RULE);

    migrate("V319");

    assertThat(
            count(
                "SELECT count(*) FROM notification_rule_selector WHERE rule_id = ?"
                    + " AND kind = 'SEAT_HOLDERS'",
                RULE))
        .isEqualTo(1);
  }
}
