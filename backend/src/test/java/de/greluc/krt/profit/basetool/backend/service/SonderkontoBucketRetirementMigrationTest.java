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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The data cut of the Sonderkonto bucket retirement (REQ-BANK-037): the SQL of migration {@code
 * V320} is run against global-role view grants and approval limits, in a rolled-back transaction.
 */
@SpringBootTest
@Transactional
class SonderkontoBucketRetirementMigrationTest {

  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
  private static final UUID ACCOUNT = UUID.fromString("66666666-6666-6666-6666-6666666976b1");
  private static final UUID LOGISTICIAN_GRANT =
      UUID.fromString("66666666-6666-6666-6666-6666666976b2");
  private static final UUID MISSION_MANAGER_GRANT =
      UUID.fromString("66666666-6666-6666-6666-6666666976b3");
  private static final UUID ALL_MEMBERS_GRANT =
      UUID.fromString("66666666-6666-6666-6666-6666666976b4");
  private static final UUID LOGISTICIAN_LIMIT =
      UUID.fromString("66666666-6666-6666-6666-6666666976b5");

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seed() {
    LegacyGlobalRoleRows.allowGlobalRole(jdbcTemplate);
    jdbcTemplate.update(
        "INSERT INTO bank_account (id, account_no, name, type) VALUES (?, ?, ?, 'SPECIAL')",
        ACCOUNT,
        "KB-CUT-976",
        "Bucket cut special");
    roleGrant(LOGISTICIAN_GRANT, "LOGISTICIAN");
    roleGrant(MISSION_MANAGER_GRANT, "MISSION_MANAGER");
    jdbcTemplate.update(
        "INSERT INTO bank_account_view_grant (id, account_id, grantee_kind)"
            + " VALUES (?, ?, 'ALL_MEMBERS')",
        ALL_MEMBERS_GRANT,
        ACCOUNT);
    jdbcTemplate.update(
        "INSERT INTO bank_account_approval_limit"
            + " (id, account_id, grantee_kind, role_code, limit_amount)"
            + " VALUES (?, ?, 'GLOBAL_ROLE', 'LOGISTICIAN', 1000)",
        LOGISTICIAN_LIMIT,
        ACCOUNT);
  }

  private void roleGrant(UUID id, String roleCode) {
    jdbcTemplate.update(
        "INSERT INTO bank_account_view_grant (id, account_id, grantee_kind, role_code)"
            + " VALUES (?, ?, 'GLOBAL_ROLE', ?)",
        id,
        ACCOUNT,
        roleCode);
  }

  private void migrate() throws IOException {
    try (var files = Files.list(MIGRATIONS)) {
      Path script =
          files
              .filter(file -> file.getFileName().toString().startsWith("V320__"))
              .findFirst()
              .orElseThrow();
      jdbcTemplate.execute(Files.readString(script, StandardCharsets.UTF_8));
    }
  }

  private int count(String sql, Object... args) {
    return jdbcTemplate.queryForObject(sql, Integer.class, args);
  }

  @Test
  void theRoleBucketsAreDeletedAndAllMembersGrantStays() throws IOException {
    migrate();

    assertThat(count("SELECT count(*) FROM bank_account_view_grant WHERE account_id = ?", ACCOUNT))
        .isEqualTo(1);
    assertThat(
            count("SELECT count(*) FROM bank_account_view_grant WHERE id = ?", ALL_MEMBERS_GRANT))
        .isEqualTo(1);
    assertThat(
            count("SELECT count(*) FROM bank_account_approval_limit WHERE account_id = ?", ACCOUNT))
        .isZero();
  }

  @Test
  void theSchemaRejectsAGlobalRoleViewGrantAfterwards() throws IOException {
    migrate();

    assertThatThrownBy(() -> roleGrant(UUID.randomUUID(), "LOGISTICIAN"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void theSchemaRejectsAGlobalRoleApprovalLimitAfterwards() throws IOException {
    migrate();

    assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO bank_account_approval_limit"
                    + " (id, account_id, grantee_kind, role_code, limit_amount)"
                    + " VALUES (?, ?, 'GLOBAL_ROLE', 'LOGISTICIAN', 1)",
                UUID.randomUUID(),
                ACCOUNT))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void eachRemovedRowLeavesOneSystemAuditEvent() throws IOException {
    migrate();

    List<Map<String, Object>> audit =
        jdbcTemplate.queryForList(
            "SELECT event_type, actor_handle, details, actor_user_id FROM bank_audit_event"
                + " WHERE account_id = ? ORDER BY event_type, details",
            ACCOUNT);
    assertThat(audit)
        .extracting(row -> row.get("event_type") + ":" + row.get("details"))
        .containsExactly(
            "APPROVAL_LIMIT_CLEARED:GLOBAL_ROLE:LOGISTICIAN",
            "BALANCE_VISIBILITY_REVOKED:GLOBAL_ROLE:LOGISTICIAN",
            "BALANCE_VISIBILITY_REVOKED:GLOBAL_ROLE:MISSION_MANAGER");
    assertThat(audit)
        .allSatisfy(row -> {
          assertThat(row.get("actor_handle")).isEqualTo("system");
          assertThat(row.get("actor_user_id")).isNull();
        });
  }
}
