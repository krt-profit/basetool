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

package de.greluc.krt.profit.basetool.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Migration test for {@code V239__drop_guest_role_and_guest_edit_token.sql} (REQ-SEC-052,
 * REQ-SEC-053): the {@code GUEST} role and {@code guest_edit_token_hash} are gone.
 *
 * <p>{@link #theDeleteLeavesAGuestOnlyAccountWithNoRolesAtAll()} re-seeds a guest-only account and
 * runs the migration's delete statements, verifying the account is left with no roles rather than
 * promoted.
 */
@SpringBootTest
@ActiveProfiles("test")
class V239MigrationTest {

  private static final String SEEDED_ROLE_CODE = "GUEST_V239_FIXTURE";

  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID seededUserId;
  private Long seededRoleId;

  @AfterEach
  void cleanup() {
    if (seededUserId != null) {
      jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", seededUserId);
      jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", seededUserId);
    }
    if (seededRoleId != null) {
      jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?", seededRoleId);
      jdbcTemplate.update("DELETE FROM user_roles WHERE role_id = ?", seededRoleId);
      jdbcTemplate.update("DELETE FROM role WHERE id = ?", seededRoleId);
    }
  }

  @Test
  void v239RemovesTheGuestRole() {
    assertThat(count("SELECT count(*) FROM role WHERE code = 'GUEST'"))
        .as("the GUEST role must not exist after V239")
        .isZero();
    assertThat(count("SELECT count(*) FROM role WHERE name = 'Guest'"))
        .as("nor under its display name")
        .isZero();
  }

  @Test
  void v239LeavesNoGuestAssignmentsBehind() {
    assertThat(
            count(
                "SELECT count(*) FROM user_roles ur LEFT JOIN role r ON r.id = ur.role_id"
                    + " WHERE r.id IS NULL"))
        .as("no user_roles row may point at a deleted role")
        .isZero();
  }

  @Test
  void v239LeavesNoNotificationRuleAddressingTheDeletedRole() {
    assertThat(count("SELECT count(*) FROM notification_rule_selector WHERE role_code = 'GUEST'"))
        .as("no notification rule may still address the deleted role")
        .isZero();
  }

  @Test
  void theSelectorCleanupIsCaseInsensitive() {
    String selectorSql =
        "SELECT count(*) FROM notification_rule_selector"
            + " WHERE kind = 'ROLE' AND upper(role_code) = 'GUEST'";
    assertThat(count(selectorSql)).as("no GUEST selector in any casing").isZero();

    assertThat(
            count(
                "SELECT count(*) FROM role WHERE upper(code) = 'GUEST' OR upper(name) ="
                    + " 'GUEST'"))
        .as("nor a role row under a differently-cased name")
        .isZero();
  }

  @Test
  void everyRoleSelectorCarriesTheCatalogueOwnCasing() {
    assertThat(
            count(
                "SELECT count(*) FROM notification_rule_selector s JOIN role r"
                    + " ON upper(s.role_code) = upper(r.code)"
                    + " WHERE s.kind = 'ROLE' AND s.role_code <> r.code"))
        .as("a selector whose casing differs from the catalogue matches no recipient at all")
        .isZero();
  }

  @Test
  void noNotificationRuleSelectorPointsAtARoleThatDoesNotExist() {
    assertThat(
            count(
                "SELECT count(*) FROM notification_rule_selector s WHERE s.kind = 'ROLE'"
                    + " AND s.role_code IS NOT NULL"
                    + " AND NOT EXISTS (SELECT 1 FROM role r WHERE r.code = s.role_code)"))
        .as("a ROLE selector naming a code the catalogue does not know cannot be saved again")
        .isZero();
  }

  @Test
  void v239DropsTheGuestEditTokenColumn() {
    assertThat(
            count(
                "SELECT count(*) FROM information_schema.columns WHERE table_name ="
                    + " 'mission_participant' AND column_name = 'guest_edit_token_hash'"))
        .as("the per-row capability token has no minting path left (REQ-SEC-018 superseded)")
        .isZero();
  }

  @Test
  void theDeleteLeavesAGuestOnlyAccountWithNoRolesAtAll() {
    seededRoleId = seedRole();
    seededUserId = seedUserHoldingOnly(seededRoleId);

    assertThat(rolesOf(seededUserId))
        .as("precondition: the account holds exactly one role")
        .isOne();

    jdbcTemplate.update(
        "DELETE FROM user_roles WHERE role_id IN (SELECT id FROM role WHERE code = ?)",
        SEEDED_ROLE_CODE);
    jdbcTemplate.update(
        "DELETE FROM role_permissions WHERE role_id IN (SELECT id FROM role WHERE code = ?)",
        SEEDED_ROLE_CODE);
    jdbcTemplate.update("DELETE FROM role WHERE code = ?", SEEDED_ROLE_CODE);

    assertThat(rolesOf(seededUserId))
        .as("a guest-only account is left with NO role — not promoted to member")
        .isZero();
    assertThat(count("SELECT count(*) FROM app_user WHERE id = '" + seededUserId + "'"))
        .as("and the account itself survives; only its assignment is gone")
        .isOne();
  }

  /**
   * Inserts a throwaway role standing in for the pre-migration {@code GUEST} row.
   *
   * @return the generated role id
   */
  private Long seedRole() {
    jdbcTemplate.update(
        "INSERT INTO role (code, name, description, version) VALUES (?, ?, ?, 0)",
        SEEDED_ROLE_CODE,
        "Guest (V239 fixture)",
        "Throwaway row for V239MigrationTest");
    return jdbcTemplate.queryForObject(
        "SELECT id FROM role WHERE code = ?", Long.class, SEEDED_ROLE_CODE);
  }

  /**
   * Inserts a throwaway account holding exactly the given role and nothing else.
   *
   * @param roleId the role to assign
   * @return the generated user id
   */
  private UUID seedUserHoldingOnly(Long roleId) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO app_user (id, username, version) VALUES (?, ?, 0)", id, "v239-fixture-" + id);
    jdbcTemplate.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", id, roleId);
    return id;
  }

  /**
   * Counts the role assignments of one account.
   *
   * @param userId the account
   * @return how many roles it holds
   */
  private int rolesOf(UUID userId) {
    Integer n =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class, userId);
    return n == null ? 0 : n;
  }

  /**
   * Runs a scalar count query.
   *
   * @param sql the query, returning exactly one numeric column
   * @return the count, or {@code 0} when the query returned {@code null}
   */
  private int count(String sql) {
    Integer n = jdbcTemplate.queryForObject(sql, Integer.class);
    return n == null ? 0 : n;
  }
}
