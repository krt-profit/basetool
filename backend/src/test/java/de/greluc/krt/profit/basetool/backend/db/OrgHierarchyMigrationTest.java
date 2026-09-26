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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies the V164 org-hierarchy migration (REQ-ORG-014/017): the new {@code org_unit} kinds and
 * parent column, the parent-kind invariants, and the at-most-two-Staffeln membership triggers.
 * Throwaway rows are removed in a finally block.
 */
@SpringBootTest
class OrgHierarchyMigrationTest {

  @MockitoBean private RoleRepository roleRepository;

  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private DataSource dataSource;

  /** Structural checks: the new column, index, dropped index, constraints and trigger all exist. */
  @Test
  void v164AddsHierarchyColumnsConstraintsAndDropsTheOneSquadronIndex() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertColumnExists(jdbc, "org_unit", "parent_org_unit_id");
    assertIndexExists(jdbc, "org_unit", "idx_org_unit_parent");

    assertIndexAbsent(jdbc, "uq_org_unit_membership_one_squadron");
    assertTriggerExists(
        jdbc, "org_unit_membership", "trg_org_unit_membership_max_two_squadron_ins");
    assertTriggerExists(
        jdbc, "org_unit_membership", "trg_org_unit_membership_max_two_squadron_upd");

    assertConstraintExists(jdbc, "chk_org_unit_ol_has_no_parent");
    assertTriggerExists(jdbc, "org_unit", "trg_org_unit_parent_ins");
  }

  /**
   * Behavioural checks on the org_unit side (no user fixtures needed): the new kinds are accepted,
   * the three-level parent chain (OL → Bereich → Staffel) is allowed, and every parent/promotion
   * invariant is rejected. Uses throwaway rows cleaned up in a finally block so the shared
   * Testcontainers schema is left untouched.
   */
  @Test
  void v164EnforcesTheThreeLevelParentHierarchy() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID olId = UUID.fromString("ffffff00-0000-0000-0000-0000000000a1");
    UUID bereichId = UUID.fromString("ffffff00-0000-0000-0000-0000000000a2");
    UUID squadronId = UUID.fromString("ffffff00-0000-0000-0000-0000000000a3");
    UUID rejectedId = UUID.fromString("ffffff00-0000-0000-0000-0000000000a4");

    cleanup(jdbc, squadronId, bereichId, olId, rejectedId);
    try {
      insertOrgUnit(jdbc, olId, "ORGANISATIONSLEITUNG", "TEST_OH_OL", "TOHOL", false, null);
      insertOrgUnit(jdbc, bereichId, "BEREICH", "TEST_OH_BER", "TOHB", false, olId);
      insertOrgUnit(jdbc, squadronId, "SQUADRON", "TEST_OH_SQ", "TOHS", true, bereichId);

      assertThat(countById(jdbc, olId)).isOne();
      assertThat(countById(jdbc, bereichId)).isOne();
      assertThat(countById(jdbc, squadronId)).isOne();

      assertThatThrownBy(
              () ->
                  insertOrgUnit(
                      jdbc, rejectedId, "BEREICH", "TEST_OH_X", "TOHX", false, squadronId))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("must have an ORGANISATIONSLEITUNG parent");

      assertThatThrownBy(
              () ->
                  insertOrgUnit(
                      jdbc, rejectedId, "ORGANISATIONSLEITUNG", "TEST_OH_X", "TOHX", false, olId))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("must not have a parent");

      assertThatThrownBy(
              () -> insertOrgUnit(jdbc, rejectedId, "BEREICH", "TEST_OH_X", "TOHX", true, olId))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("chk_org_unit_promotion_only_squadron");
    } finally {
      cleanup(jdbc, squadronId, bereichId, olId, rejectedId);
    }
  }

  /**
   * Verifies the at-most-two-Staffeln trigger (REQ-ORG-017): a third Staffel is rejected on INSERT
   * and UPDATE, while a re-point that stays at two is allowed.
   */
  @Test
  void v164EnforcesAtMostTwoSquadronMembershipsOnInsertAndUpdate() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID userId = UUID.fromString("ffffff00-0000-0000-0000-0000000000b0");
    UUID sqA = UUID.fromString("ffffff00-0000-0000-0000-0000000000b1");
    UUID sqB = UUID.fromString("ffffff00-0000-0000-0000-0000000000b2");
    UUID sqC = UUID.fromString("ffffff00-0000-0000-0000-0000000000b3");
    UUID skX = UUID.fromString("ffffff00-0000-0000-0000-0000000000b4");

    cleanupMemberships(jdbc, userId);
    cleanup(jdbc, sqA, sqB, sqC, skX);
    cleanupUser(jdbc, userId);
    try {
      insertUser(jdbc, userId);
      insertOrgUnit(jdbc, sqA, "SQUADRON", "TEST_OH_SQA", "TOSA", false, null);
      insertOrgUnit(jdbc, sqB, "SQUADRON", "TEST_OH_SQB", "TOSB", false, null);
      insertOrgUnit(jdbc, sqC, "SQUADRON", "TEST_OH_SQC", "TOSC", false, null);
      insertOrgUnit(jdbc, skX, "SPECIAL_COMMAND", "TEST_OH_SKX", "TOSX", false, null);

      insertMembership(jdbc, userId, sqA);
      insertMembership(jdbc, userId, sqB);
      assertThat(squadronMembershipCount(jdbc, userId)).isEqualTo(2);

      assertThatThrownBy(() -> insertMembership(jdbc, userId, sqC))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("at most two Staffeln");

      insertMembership(jdbc, userId, skX);
      assertThat(squadronMembershipCount(jdbc, userId)).isEqualTo(2);

      repointMembership(jdbc, userId, sqA, sqC);
      assertThat(membershipExists(jdbc, userId, sqC)).isTrue();
      assertThat(membershipExists(jdbc, userId, sqA)).isFalse();
      assertThat(squadronMembershipCount(jdbc, userId)).isEqualTo(2);

      assertThatThrownBy(() -> repointMembership(jdbc, userId, skX, sqA))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("at most two Staffeln");
    } finally {
      cleanupMemberships(jdbc, userId);
      cleanup(jdbc, sqA, sqB, sqC, skX);
      cleanupUser(jdbc, userId);
    }
  }

  /**
   * V165 (REQ-ORG-017): the cross-row "a leader holds no Staffel" invariant is enforced by a
   * trigger on {@code org_unit_membership} firing on both INSERT and UPDATE. The behavioural
   * rejection paths are covered through the service layer (where user fixtures exist); here the
   * trigger's presence is the early-warning canary.
   */
  @Test
  void v165AddsLeaderExcludesSquadronTrigger() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertTriggerExists(
        jdbc, "org_unit_membership", "trg_org_unit_membership_leader_excl_squadron_ins");
    assertTriggerExists(
        jdbc, "org_unit_membership", "trg_org_unit_membership_leader_excl_squadron_upd");
  }

  /**
   * Verifies V184 (REQ-ROLE-001): the kind-scoped {@code role} column defaults to {@code MEMBER}
   * and rejects a rank not matching the org-unit kind.
   */
  @Test
  void v184AddsKindScopedRoleColumn() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertColumnExists(jdbc, "org_unit_membership", "role");
    assertConstraintExists(jdbc, "chk_org_unit_membership_role_kind");

    UUID userId = UUID.fromString("ffffff00-0000-0000-0000-0000000000c0");
    UUID squadronId = UUID.fromString("ffffff00-0000-0000-0000-0000000000c1");
    UUID skId = UUID.fromString("ffffff00-0000-0000-0000-0000000000c2");
    cleanupMemberships(jdbc, userId);
    cleanup(jdbc, squadronId, skId);
    cleanupUser(jdbc, userId);
    try {
      insertUser(jdbc, userId);
      insertOrgUnit(jdbc, squadronId, "SQUADRON", "TEST_RM_SQ", "TRMS", false, null);
      insertOrgUnit(jdbc, skId, "SPECIAL_COMMAND", "TEST_RM_SK", "TRMK", false, null);

      insertMembership(jdbc, userId, squadronId);
      assertThat(membershipRole(jdbc, userId, squadronId)).isEqualTo("MEMBER");

      updateMembershipRole(jdbc, userId, squadronId, "STAFFELLEITER");
      assertThat(membershipRole(jdbc, userId, squadronId)).isEqualTo("STAFFELLEITER");

      insertMembership(jdbc, userId, skId);
      assertThatThrownBy(() -> updateMembershipRole(jdbc, userId, skId, "STAFFELLEITER"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("chk_org_unit_membership_role_kind");
    } finally {
      cleanupMemberships(jdbc, userId);
      cleanup(jdbc, squadronId, skId);
      cleanupUser(jdbc, userId);
    }
  }

  /**
   * Verifies V185 (REQ-ROLE-003): {@code kommando_group} belongs to a SQUADRON, at most four per
   * squadron, and {@code kommando_group_id} is confined to in-group squadron ranks.
   */
  @Test
  void v185CreatesKommandoGroupWithSquadronAndCardinalityRules() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertColumnExists(jdbc, "kommando_group", "squadron_org_unit_id");
    assertColumnExists(jdbc, "org_unit_membership", "kommando_group_id");
    assertConstraintExists(jdbc, "chk_org_unit_membership_kommando_group_role");
    assertTriggerExists(jdbc, "kommando_group", "trg_kommando_group_squadron_ins");
    assertTriggerExists(jdbc, "kommando_group", "trg_kommando_group_max_four_ins");

    UUID squadronId = UUID.fromString("ffffff00-0000-0000-0000-0000000000d0");
    UUID skId = UUID.fromString("ffffff00-0000-0000-0000-0000000000d1");
    UUID g1 = UUID.fromString("ffffff00-0000-0000-0000-0000000000e1");
    UUID g2 = UUID.fromString("ffffff00-0000-0000-0000-0000000000e2");
    UUID g3 = UUID.fromString("ffffff00-0000-0000-0000-0000000000e3");
    UUID g4 = UUID.fromString("ffffff00-0000-0000-0000-0000000000e4");
    UUID g5 = UUID.fromString("ffffff00-0000-0000-0000-0000000000e5");
    cleanupKommandoGroups(jdbc, squadronId, skId);
    cleanup(jdbc, squadronId, skId);
    try {
      insertOrgUnit(jdbc, squadronId, "SQUADRON", "TEST_KG_SQ", "TKGS", false, null);
      insertOrgUnit(jdbc, skId, "SPECIAL_COMMAND", "TEST_KG_SK", "TKGK", false, null);

      assertThatThrownBy(() -> insertKommandoGroup(jdbc, g1, skId, "Bad"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("must belong to a SQUADRON");

      insertKommandoGroup(jdbc, g1, squadronId, "Alpha");
      insertKommandoGroup(jdbc, g2, squadronId, "Bravo");
      insertKommandoGroup(jdbc, g3, squadronId, "Charlie");
      insertKommandoGroup(jdbc, g4, squadronId, "Delta");
      assertThatThrownBy(() -> insertKommandoGroup(jdbc, g5, squadronId, "Echo"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("at most four Kommandogruppen");
    } finally {
      cleanupKommandoGroups(jdbc, squadronId, skId);
      cleanup(jdbc, squadronId, skId);
    }
  }

  /**
   * Verifies V186 (REQ-ROLE-006): a {@code COMMAND_LEAD} node may link one Kommandogruppe, a second
   * node for the same group is rejected, and non-{@code COMMAND_LEAD} ranks cannot link one.
   */
  @Test
  void v186LinksOrgChartCommandNodeToKommandoGroup() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertColumnExists(jdbc, "org_chart_position", "kommando_group_id");
    assertConstraintExists(jdbc, "chk_org_chart_kommando_group_type");
    assertConstraintExists(jdbc, "fk_org_chart_position_kommando_group");
    assertIndexExists(jdbc, "org_chart_position", "uq_org_chart_one_command_per_group");

    UUID squadronId = UUID.fromString("ffffff00-0000-0000-0000-0000000000f0");
    UUID userId = UUID.fromString("ffffff00-0000-0000-0000-0000000000f1");
    UUID groupId = UUID.fromString("ffffff00-0000-0000-0000-0000000000f2");
    UUID cmd1 = UUID.fromString("ffffff00-0000-0000-0000-0000000000f3");
    UUID cmd2 = UUID.fromString("ffffff00-0000-0000-0000-0000000000f4");
    UUID lead = UUID.fromString("ffffff00-0000-0000-0000-0000000000f5");
    cleanupOrgChartPositions(jdbc, cmd1, cmd2, lead);
    cleanupKommandoGroups(jdbc, squadronId);
    cleanupMemberships(jdbc, userId);
    cleanup(jdbc, squadronId);
    cleanupUser(jdbc, userId);
    try {
      insertUser(jdbc, userId);
      insertOrgUnit(jdbc, squadronId, "SQUADRON", "TEST_OC_SQ", "TOCS", false, null);
      insertKommandoGroup(jdbc, groupId, squadronId, "Alpha");

      insertOrgChartPosition(jdbc, cmd1, "COMMAND_LEAD", squadronId, null, "Alpha", groupId);
      assertThat(orgChartPositionCount(jdbc, cmd1)).isOne();

      assertThatThrownBy(
              () ->
                  insertOrgChartPosition(
                      jdbc, cmd2, "COMMAND_LEAD", squadronId, null, "Alpha2", groupId))
          .isInstanceOf(DataAccessException.class);

      assertThatThrownBy(
              () ->
                  insertOrgChartPosition(
                      jdbc, lead, "SQUADRON_LEAD", squadronId, userId, null, groupId))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("chk_org_chart_kommando_group_type");
    } finally {
      cleanupOrgChartPositions(jdbc, cmd1, cmd2, lead);
      cleanupKommandoGroups(jdbc, squadronId);
      cleanupMemberships(jdbc, userId);
      cleanup(jdbc, squadronId);
      cleanupUser(jdbc, userId);
    }
  }

  /**
   * Verifies V187 (REQ-ROLE-001): the boolean leadership columns and their CHECKs are absent, while
   * the {@code role} column and the {@code enforce_leader_excludes_squadron} trigger remain.
   */
  @Test
  void v187DropsBooleanFlagsAndConstraints() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    assertColumnAbsent(jdbc, "org_unit_membership", "is_lead");
    assertColumnAbsent(jdbc, "org_unit_membership", "is_bereichsleiter");
    assertColumnAbsent(jdbc, "org_unit_membership", "is_bereichskoordinator");
    assertColumnAbsent(jdbc, "org_unit_membership", "is_bereichsoperator");
    assertColumnAbsent(jdbc, "org_unit_membership", "is_ol_member");

    assertConstraintAbsent(jdbc, "chk_org_unit_membership_lead_only_on_special_command");
    assertConstraintAbsent(jdbc, "chk_org_unit_membership_bereich_flags_only_on_bereich");
    assertConstraintAbsent(jdbc, "chk_org_unit_membership_ol_flag_only_on_ol");

    assertColumnExists(jdbc, "org_unit_membership", "role");
    assertConstraintExists(jdbc, "chk_org_unit_membership_role_kind");
    assertTriggerExists(
        jdbc, "org_unit_membership", "trg_org_unit_membership_leader_excl_squadron_ins");
    assertTriggerExists(
        jdbc, "org_unit_membership", "trg_org_unit_membership_leader_excl_squadron_upd");
  }

  /**
   * Verifies that {@code enforce_leader_excludes_squadron} reads {@code role}: squadron ranks are
   * exempt, while {@code SK_LEAD} is rejected for a Staffel member (REQ-ORG-017).
   */
  @Test
  void v187LeaderExclusionTriggerReadsRole_squadronRanksExempt() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID userId = UUID.fromString("ffffff00-0000-0000-0000-0000000000d0");
    UUID squadronId = UUID.fromString("ffffff00-0000-0000-0000-0000000000d1");
    UUID skId = UUID.fromString("ffffff00-0000-0000-0000-0000000000d2");
    cleanupMemberships(jdbc, userId);
    cleanup(jdbc, squadronId, skId);
    cleanupUser(jdbc, userId);
    try {
      insertUser(jdbc, userId);
      insertOrgUnit(jdbc, squadronId, "SQUADRON", "TEST_V187_SQ", "TV87S", false, null);
      insertOrgUnit(jdbc, skId, "SPECIAL_COMMAND", "TEST_V187_SK", "TV87K", false, null);

      insertMembership(jdbc, userId, squadronId);
      updateMembershipRole(jdbc, userId, squadronId, "STAFFELLEITER");
      assertThat(membershipRole(jdbc, userId, squadronId)).isEqualTo("STAFFELLEITER");

      insertMembership(jdbc, userId, skId);
      assertThatThrownBy(() -> updateMembershipRole(jdbc, userId, skId, "SK_LEAD"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("may not belong to a Staffel");
    } finally {
      cleanupMemberships(jdbc, userId);
      cleanup(jdbc, squadronId, skId);
      cleanupUser(jdbc, userId);
    }
  }

  /**
   * Verifies V188 (REQ-ROLE-003): a partial unique index rejects a second STAFFELLEITER on the same
   * Staffel.
   */
  @Test
  void v188SquadronRankSingletonIndexes() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertIndexExists(jdbc, "org_unit_membership", "uq_org_unit_membership_one_staffelleiter");
    assertIndexExists(
        jdbc, "org_unit_membership", "uq_org_unit_membership_one_kommandoleiter_per_group");
    assertIndexExists(jdbc, "org_unit_membership", "uq_org_unit_membership_one_stellv_per_group");

    UUID userA = UUID.fromString("ffffff00-0000-0000-0000-0000000000e0");
    UUID userB = UUID.fromString("ffffff00-0000-0000-0000-0000000000e1");
    UUID squadronId = UUID.fromString("ffffff00-0000-0000-0000-0000000000e2");
    cleanupMemberships(jdbc, userA);
    cleanupMemberships(jdbc, userB);
    cleanup(jdbc, squadronId);
    cleanupUser(jdbc, userA);
    cleanupUser(jdbc, userB);
    try {
      insertUser(jdbc, userA);
      insertUser(jdbc, userB);
      insertOrgUnit(jdbc, squadronId, "SQUADRON", "TEST_V188_SQ", "TV88S", false, null);

      insertMembership(jdbc, userA, squadronId);
      updateMembershipRole(jdbc, userA, squadronId, "STAFFELLEITER");
      assertThat(membershipRole(jdbc, userA, squadronId)).isEqualTo("STAFFELLEITER");

      insertMembership(jdbc, userB, squadronId);
      assertThatThrownBy(() -> updateMembershipRole(jdbc, userB, squadronId, "STAFFELLEITER"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("uq_org_unit_membership_one_staffelleiter");
    } finally {
      cleanupMemberships(jdbc, userA);
      cleanupMemberships(jdbc, userB);
      cleanup(jdbc, squadronId);
      cleanupUser(jdbc, userA);
      cleanupUser(jdbc, userB);
    }
  }

  private static void insertOrgUnit(
      JdbcTemplate jdbc,
      UUID id,
      String kind,
      String name,
      String shorthand,
      boolean promotionEnabled,
      UUID parentId) {
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active, is_promotion_enabled,"
            + " is_profit_eligible, parent_org_unit_id) VALUES (?, ?, ?, ?, TRUE, ?, FALSE, ?)",
        id,
        kind,
        name,
        shorthand,
        promotionEnabled,
        parentId);
  }

  private static int countById(JdbcTemplate jdbc, UUID id) {
    Integer count =
        jdbc.queryForObject("SELECT COUNT(*) FROM org_unit WHERE id = ?", Integer.class, id);
    return count == null ? 0 : count;
  }

  /** Deletes the throwaway rows child-first so the self-referential FK never blocks the cleanup. */
  private static void cleanup(JdbcTemplate jdbc, UUID... idsChildFirst) {
    for (UUID id : idsChildFirst) {
      jdbc.update("DELETE FROM org_unit WHERE id = ?", id);
    }
  }

  /** Inserts a minimal throwaway user; only {@code id} is required (other columns default). */
  private static void insertUser(JdbcTemplate jdbc, UUID id) {
    jdbc.update("INSERT INTO app_user (id) VALUES (?)", id);
  }

  /**
   * Inserts a membership; {@code kind} is denormalised from the referenced {@code org_unit} by the
   * V98 sync trigger and the leadership flags default to {@code false}, so neither is set here.
   */
  private static void insertMembership(JdbcTemplate jdbc, UUID userId, UUID orgUnitId) {
    jdbc.update(
        "INSERT INTO org_unit_membership (user_id, org_unit_id) VALUES (?, ?)", userId, orgUnitId);
  }

  /** Re-points a membership to a different org unit, exercising the UPDATE counting trigger. */
  private static void repointMembership(
      JdbcTemplate jdbc, UUID userId, UUID fromOrgUnitId, UUID toOrgUnitId) {
    jdbc.update(
        "UPDATE org_unit_membership SET org_unit_id = ? WHERE user_id = ? AND org_unit_id = ?",
        toOrgUnitId,
        userId,
        fromOrgUnitId);
  }

  private static boolean membershipExists(JdbcTemplate jdbc, UUID userId, UUID orgUnitId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM org_unit_membership WHERE user_id = ? AND org_unit_id = ?",
            Integer.class,
            userId,
            orgUnitId);
    return count != null && count > 0;
  }

  /** Reads the unified rank ({@code role}) of a single membership row (V184). */
  private static String membershipRole(JdbcTemplate jdbc, UUID userId, UUID orgUnitId) {
    return jdbc.queryForObject(
        "SELECT role FROM org_unit_membership WHERE user_id = ? AND org_unit_id = ?",
        String.class,
        userId,
        orgUnitId);
  }

  /** Sets the unified rank on a membership row, exercising the V184 kind-scoped CHECK. */
  private static void updateMembershipRole(
      JdbcTemplate jdbc, UUID userId, UUID orgUnitId, String role) {
    jdbc.update(
        "UPDATE org_unit_membership SET role = ? WHERE user_id = ? AND org_unit_id = ?",
        role,
        userId,
        orgUnitId);
  }

  /**
   * Inserts a Kommandogruppe (V185); {@code version} defaults and timestamps are left to defaults.
   */
  private static void insertKommandoGroup(
      JdbcTemplate jdbc, UUID id, UUID squadronOrgUnitId, String name) {
    jdbc.update(
        "INSERT INTO kommando_group (id, squadron_org_unit_id, name) VALUES (?, ?, ?)",
        id,
        squadronOrgUnitId,
        name);
  }

  /** Removes every Kommandogruppe of the given org units before the org-unit rows are deleted. */
  private static void cleanupKommandoGroups(JdbcTemplate jdbc, UUID... squadronIds) {
    for (UUID id : squadronIds) {
      jdbc.update("DELETE FROM kommando_group WHERE squadron_org_unit_id = ?", id);
    }
  }

  /** Inserts an org-chart position (V186); version / sort_index / timestamps fall to defaults. */
  private static void insertOrgChartPosition(
      JdbcTemplate jdbc,
      UUID id,
      String positionType,
      UUID orgUnitId,
      UUID userId,
      String name,
      UUID kommandoGroupId) {
    jdbc.update(
        "INSERT INTO org_chart_position (id, position_type, org_unit_id, user_id, name,"
            + " kommando_group_id) VALUES (?, ?, ?, ?, ?, ?)",
        id,
        positionType,
        orgUnitId,
        userId,
        name,
        kommandoGroupId);
  }

  private static int orgChartPositionCount(JdbcTemplate jdbc, UUID id) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM org_chart_position WHERE id = ?", Integer.class, id);
    return count == null ? 0 : count;
  }

  /** Removes throwaway org-chart positions before their group / org-unit rows are deleted. */
  private static void cleanupOrgChartPositions(JdbcTemplate jdbc, UUID... ids) {
    for (UUID id : ids) {
      jdbc.update("DELETE FROM org_chart_position WHERE id = ?", id);
    }
  }

  private static int squadronMembershipCount(JdbcTemplate jdbc, UUID userId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM org_unit_membership WHERE user_id = ? AND kind = 'SQUADRON'",
            Integer.class,
            userId);
    return count == null ? 0 : count;
  }

  /** Removes every membership of the throwaway user before its org units / row are deleted. */
  private static void cleanupMemberships(JdbcTemplate jdbc, UUID userId) {
    jdbc.update("DELETE FROM org_unit_membership WHERE user_id = ?", userId);
  }

  /** Removes the throwaway user row (memberships must be gone first). */
  private static void cleanupUser(JdbcTemplate jdbc, UUID id) {
    jdbc.update("DELETE FROM app_user WHERE id = ?", id);
  }

  private static void assertColumnExists(JdbcTemplate jdbc, String table, String column) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = ? AND column_name = ?",
            String.class,
            table,
            column);
    assertThat(rows).as("Expected column %s.%s to exist", table, column).isNotEmpty();
  }

  private static void assertIndexExists(JdbcTemplate jdbc, String table, String indexName) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = ? AND indexname = ?",
            String.class,
            table,
            indexName);
    assertThat(rows).as("Expected index %s on table %s", indexName, table).isNotEmpty();
  }

  private static void assertIndexAbsent(JdbcTemplate jdbc, String indexName) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE indexname = ?", String.class, indexName);
    assertThat(rows).as("Expected index %s to have been dropped by V164", indexName).isEmpty();
  }

  private static void assertConstraintExists(JdbcTemplate jdbc, String constraintName) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT conname FROM pg_constraint WHERE conname = ?", String.class, constraintName);
    assertThat(rows).as("Expected constraint %s to exist", constraintName).isNotEmpty();
  }

  private static void assertColumnAbsent(JdbcTemplate jdbc, String table, String column) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns"
                + " WHERE table_name = ? AND column_name = ?",
            String.class,
            table,
            column);
    assertThat(rows).as("Expected column %s.%s to have been dropped", table, column).isEmpty();
  }

  private static void assertConstraintAbsent(JdbcTemplate jdbc, String constraintName) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT conname FROM pg_constraint WHERE conname = ?", String.class, constraintName);
    assertThat(rows).as("Expected constraint %s to have been dropped", constraintName).isEmpty();
  }

  private static void assertTriggerExists(JdbcTemplate jdbc, String table, String triggerName) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid"
                + " WHERE c.relname = ? AND t.tgname = ?",
            String.class,
            table,
            triggerName);
    assertThat(rows).as("Expected trigger %s on table %s", triggerName, table).isNotEmpty();
  }
}
