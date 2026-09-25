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
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Enforces ADR-0142 point 3 in the live schema: every column named as a user identifier carries a
 * foreign key to {@code app_user(id)}. Detection is by column name only.
 */
@SpringBootTest
class UserIdentityColumnForeignKeyTest {

  @MockitoBean private RoleRepository roleRepository;

  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private DataSource dataSource;

  /**
   * User-identity columns that deliberately carry no foreign key, each justified by a {@code
   * COMMENT ON COLUMN}: the audit targets, which must outlive the account (REQ-AUDIT-001).
   */
  private static final Set<String> EXEMPT_COLUMNS =
      Set.of("audit_event.target_user_id", "bank_audit_event.target_user_id");

  /**
   * Columns matching the naming rule that hold no {@code app_user.id}: {@code
   * app_user.discord_user_id} holds a Discord snowflake id (REQ-SEC-017).
   */
  private static final Set<String> FOREIGN_SYSTEM_ID_COLUMNS = Set.of("app_user.discord_user_id");

  /**
   * Fails when a column named {@code user_id}, {@code *_user_id} or {@code *_sub} has no foreign
   * key to {@code app_user(id)}; a minimum inspected-column count guards against an empty match.
   */
  @Test
  @DisplayName("every user-identity column has a foreign key to app_user(id)")
  void everyUserIdentityColumnHasAppUserForeignKey() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    List<String> candidates =
        jdbc.queryForList(
            """
            SELECT c.table_name || '.' || c.column_name
            FROM information_schema.columns c
            JOIN information_schema.tables t
              ON t.table_schema = c.table_schema
             AND t.table_name = c.table_name
             AND t.table_type = 'BASE TABLE'
            WHERE c.table_schema = current_schema()
              AND (c.column_name = 'user_id'
                   OR c.column_name LIKE '%\\_user\\_id'
                   OR c.column_name LIKE '%\\_sub')
            ORDER BY 1
            """,
            String.class);

    assertThat(candidates)
        .as(
            "user-identity columns found by the naming rule (a query matching none passes"
                + " vacuously)")
        .hasSizeGreaterThanOrEqualTo(30);

    List<String> withAppUserFk =
        jdbc.queryForList(
            """
            SELECT src.relname || '.' || att.attname
            FROM pg_constraint con
            JOIN pg_class src ON src.oid = con.conrelid
            JOIN pg_class tgt ON tgt.oid = con.confrelid
            JOIN unnest(con.conkey) AS k(attnum) ON TRUE
            JOIN pg_attribute att ON att.attrelid = src.oid AND att.attnum = k.attnum
            WHERE con.contype = 'f'
              AND tgt.relname = 'app_user'
            """,
            String.class);

    assertThat(candidates)
        .as(
            """
            Every column named for a user identifier must carry a foreign key to app_user(id) with \
            an explicit ON DELETE clause (ADR-0142 point 3, REQ-DATA-008). Without one nothing \
            cascades, the rows outlive the account, and a returning Keycloak subject re-adopts \
            them. Add the constraint in a migration -- or, if the column must outlive the account \
            the way the audit trail does, say so in a COMMENT ON COLUMN and list it in \
            EXEMPT_COLUMNS.\
            """)
        .allSatisfy(
            column ->
                assertThat(
                        withAppUserFk.contains(column)
                            || EXEMPT_COLUMNS.contains(column)
                            || FOREIGN_SYSTEM_ID_COLUMNS.contains(column))
                    .as("%s has a foreign key to app_user(id), or is a recorded exemption", column)
                    .isTrue());
  }

  /** Verifies the five V235 foreign keys by name and that each uses {@code ON DELETE CASCADE}. */
  @Test
  @DisplayName("the five V235 foreign keys exist and cascade")
  void v235ForeignKeysCascadeOnDelete() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertCascadingAppUserFk(jdbc, "notification", "fk_notification_recipient");
    assertCascadingAppUserFk(
        jdbc, "notification_rule_selector", "fk_notification_rule_selector_user");
    assertCascadingAppUserFk(jdbc, "personal_blueprint", "fk_personal_blueprint_owner");
    assertCascadingAppUserFk(jdbc, "personal_inventory_item", "fk_personal_inventory_item_owner");
    assertCascadingAppUserFk(jdbc, "member_evaluation", "fk_member_evaluation_user");
  }

  /**
   * Asserts that {@code table} carries a foreign key named {@code constraint} that references
   * {@code app_user} and deletes its rows with the parent.
   *
   * @param jdbc the template bound to the live Postgres test schema
   * @param table the referencing table
   * @param constraint the constraint name the migration gave it
   */
  private static void assertCascadingAppUserFk(JdbcTemplate jdbc, String table, String constraint) {
    List<String> actions =
        jdbc.queryForList(
            """
            SELECT con.confdeltype::text
            FROM pg_constraint con
            JOIN pg_class src ON src.oid = con.conrelid
            JOIN pg_class tgt ON tgt.oid = con.confrelid
            WHERE con.contype = 'f'
              AND con.conname = ?
              AND src.relname = ?
              AND tgt.relname = 'app_user'
            """,
            String.class,
            constraint,
            table);
    assertThat(actions)
        .as("Expected foreign key %s on %s referencing app_user(id)", constraint, table)
        .hasSize(1);
    assertThat(actions.get(0))
        .as("%s must be ON DELETE CASCADE ('c'), not NO ACTION or SET NULL", constraint)
        .isEqualTo("c");
  }
}
