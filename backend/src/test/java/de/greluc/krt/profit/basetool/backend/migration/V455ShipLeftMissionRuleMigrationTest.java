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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Verifies that V455 moves every rule of the gap notice's event onto {@code
 * HANGAR_SHIP_LEFT_MISSION}, rewording the seeded rule's description only while an admin has not
 * changed it (REQ-HANGAR-006).
 */
@SpringBootTest
class V455ShipLeftMissionRuleMigrationTest {

  private static final String SCRIPT = "db/migration/V455__rename_ship_left_mission_rule.sql";
  private static final String OLD_EVENT = "HANGAR_SHIP_DELETED_FROM_MISSION";
  private static final String NEW_EVENT = "HANGAR_SHIP_LEFT_MISSION";
  private static final UUID SEEDED_RULE = UUID.fromString("62200000-0000-0000-0000-000000000033");
  private static final String SEEDED_DESCRIPTION =
      "Default: tell the mission leadership and the unit's responsible member that a deleted ship"
          + " drops out of a planned mission (REQ-HANGAR-006).";
  private static final String NEW_DESCRIPTION =
      "Default: tell the mission leadership and the unit's responsible member that an assigned"
          + " ship leaves a planned mission (REQ-HANGAR-006).";

  @Autowired private DataSource dataSource;

  @Test
  void theMigratedSchemaHoldsTheSeededRuleUnderItsNewEventOnly() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(eventOf(connection, SEEDED_RULE)).isEqualTo(NEW_EVENT);
      assertThat(descriptionOf(connection, SEEDED_RULE)).isEqualTo(NEW_DESCRIPTION);
      assertThat(rulesOf(connection, OLD_EVENT)).isZero();
    }
  }

  @Test
  void renamesEveryRuleOfTheOldEventAndKeepsAnAdminsOwnDescription() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      reset(connection, SEEDED_RULE, OLD_EVENT, SEEDED_DESCRIPTION);
      UUID adminRule = rule(connection, OLD_EVENT, "Tell the hangar crew as well");
      int selectorsBefore = selectorsOf(connection, SEEDED_RULE);

      ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCRIPT));

      assertThat(eventOf(connection, SEEDED_RULE)).isEqualTo(NEW_EVENT);
      assertThat(descriptionOf(connection, SEEDED_RULE)).isEqualTo(NEW_DESCRIPTION);
      assertThat(selectorsOf(connection, SEEDED_RULE)).isEqualTo(selectorsBefore).isPositive();
      assertThat(eventOf(connection, adminRule)).isEqualTo(NEW_EVENT);
      assertThat(descriptionOf(connection, adminRule)).isEqualTo("Tell the hangar crew as well");
      assertThat(rulesOf(connection, OLD_EVENT)).isZero();
      connection.rollback();
    }
  }

  @Test
  void keepsTheSeededRulesDescriptionAnAdminRewrote() throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      reset(connection, SEEDED_RULE, OLD_EVENT, "Our own wording");

      ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCRIPT));

      assertThat(eventOf(connection, SEEDED_RULE)).isEqualTo(NEW_EVENT);
      assertThat(descriptionOf(connection, SEEDED_RULE)).isEqualTo("Our own wording");
      connection.rollback();
    }
  }

  private static void reset(
      @NotNull Connection connection,
      @NotNull UUID id,
      @NotNull String eventType,
      @NotNull String description)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE notification_rule SET event_type = ?, description = ? WHERE id = ?")) {
      update.setString(1, eventType);
      update.setString(2, description);
      update.setObject(3, id);
      assertThat(update.executeUpdate()).isOne();
    }
  }

  private static @NotNull UUID rule(
      @NotNull Connection connection, @NotNull String eventType, @NotNull String description)
      throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO notification_rule (id, event_type, notification_type, description)"
                + " VALUES (?, ?, 'HANGAR_SHIP_REMOVED_FROM_UNIT', ?)")) {
      insert.setObject(1, id);
      insert.setString(2, eventType);
      insert.setString(3, description);
      insert.executeUpdate();
    }
    return id;
  }

  private static String eventOf(@NotNull Connection connection, @NotNull UUID id)
      throws SQLException {
    return column(connection, "SELECT event_type FROM notification_rule WHERE id = ?", id);
  }

  private static String descriptionOf(@NotNull Connection connection, @NotNull UUID id)
      throws SQLException {
    return column(connection, "SELECT description FROM notification_rule WHERE id = ?", id);
  }

  private static String column(
      @NotNull Connection connection, @NotNull String query, @NotNull UUID id) throws SQLException {
    try (PreparedStatement select = connection.prepareStatement(query)) {
      select.setObject(1, id);
      try (ResultSet rows = select.executeQuery()) {
        assertThat(rows.next()).as("rule %s exists", id).isTrue();
        return rows.getString(1);
      }
    }
  }

  private static int rulesOf(@NotNull Connection connection, @NotNull String eventType)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT count(*) FROM notification_rule WHERE event_type = ?")) {
      select.setString(1, eventType);
      try (ResultSet rows = select.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static int selectorsOf(@NotNull Connection connection, @NotNull UUID ruleId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT count(*) FROM notification_rule_selector WHERE rule_id = ?")) {
      select.setObject(1, ruleId);
      try (ResultSet rows = select.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }
}
