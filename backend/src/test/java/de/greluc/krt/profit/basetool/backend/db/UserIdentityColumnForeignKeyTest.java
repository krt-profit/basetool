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
 * Guards ADR-0142 point 3 in the live schema: <b>every column holding an {@code app_user.id}
 * carries a foreign key to {@code app_user(id)}</b>.
 *
 * <p>Five columns did not (issue #1638), which is how each of them outlived the accounts they
 * belonged to: nothing cascaded, no retention job reached them, they became undiscoverable once
 * every lookup key had left the roster, and a returning Keycloak subject silently re-adopted them.
 * V227 purged what earlier deletions had already leaked and V235 added the constraints — but a
 * migration only fixes the columns that existed when it was written. This test is what stops the
 * sixth from appearing: a new {@code *_user_id} or {@code *_sub} column without a foreign key fails
 * the build, at the point the column is added rather than at the deletion that leaks because of it.
 *
 * <p>Detection is by <b>name</b>, not by type or by content, because that is the only signal
 * available before a row exists. A column that holds a user id under some other name is invisible
 * here — which is itself an argument for the naming ADR-0142 point 1 settles.
 */
@SpringBootTest
class UserIdentityColumnForeignKeyTest {

  @MockitoBean private RoleRepository roleRepository;

  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private DataSource dataSource;

  /**
   * Columns matching the naming rule that deliberately carry <b>no</b> foreign key, each for a
   * reason recorded in the schema itself via {@code COMMENT ON COLUMN} (V235).
   *
   * <p>The audit trail must outlive the account it is about (REQ-AUDIT-001): a foreign key would
   * either delete the evidence with the member or block the deletion outright. Both tables snapshot
   * a NOT NULL handle beside the id, so a dangling target still renders.
   *
   * <p>Adding an entry here is a deliberate act that needs the same justification in the migration.
   * It is not the way to make this test pass.
   */
  private static final Set<String> EXEMPT_COLUMNS =
      Set.of("audit_event.target_user_id", "bank_audit_event.target_user_id");

  /**
   * Columns the naming rule matches that do not hold an {@code app_user.id} at all, and so are not
   * exemptions from anything -- they are outside the rule's subject.
   *
   * <p>{@code app_user.discord_user_id} holds the <b>Discord</b> account's snowflake id (V173,
   * REQ-SEC-017). A foreign key to {@code app_user(id)} would be nonsense there, and the column
   * carries its own uniqueness constraint.
   *
   * <p>Kept separate from {@link #EXEMPT_COLUMNS} deliberately: conflating "this is not a user id"
   * with "this is a user id we chose not to constrain" is how a real leak would end up filed as a
   * false positive.
   */
  private static final Set<String> FOREIGN_SYSTEM_ID_COLUMNS = Set.of("app_user.discord_user_id");

  /**
   * Fails when a column whose name marks it as a user identifier has no foreign key to {@code
   * app_user(id)}.
   *
   * <p>The name rule is {@code user_id}, {@code *_user_id} and {@code *_sub} — the three shapes the
   * schema actually uses for this. {@code *_sub} is matched because four such columns still exist
   * (they are renamed to {@code *_user_id} by #1640); keeping the pattern after that rename costs
   * nothing and catches a relapse.
   *
   * <p>A lower bound on the number of inspected columns guards against the query silently matching
   * nothing — a rename of {@code app_user} or a schema-name change would otherwise turn this test
   * green by inspecting an empty set.
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

  /**
   * Pins the five constraints V235 added, by name and by {@code ON DELETE} action.
   *
   * <p>The sweep above only proves <em>a</em> foreign key exists. These five were the defect, and
   * what makes them a fix rather than a formality is {@code CASCADE}: {@code NO ACTION} would turn
   * every user deletion into a {@code 23503} instead, and {@code SET NULL} would keep the rows and
   * orphan them by a different route — including the notification rule selectors, which would go on
   * matching and minting notifications for a member that no longer exists.
   */
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
