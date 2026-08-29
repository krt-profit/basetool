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
import de.greluc.krt.profit.basetool.backend.service.UserAccountMergeService;
import java.util.LinkedHashSet;
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
 * Makes the account merge's table enumeration exhaustive <b>by construction</b>.
 *
 * <p>{@code UserAccountMergeService} decides, table by table, what follows a member onto their
 * surviving account and what stays with the act it records. A list like that rots the moment
 * someone adds a column: the merge would silently leave those rows behind, and the only way to find
 * out would be a member noticing something of theirs is missing.
 *
 * <p>So the list is not trusted. This reads every foreign key into {@code app_user} out of the live
 * schema and requires each one to appear in exactly one of the two classifications. A new
 * user-referencing column cannot be forgotten — it can only be classified, deliberately, by someone
 * who had to think about which side it belongs on.
 *
 * <p>The reverse direction is checked too: a classification entry naming a column that no longer
 * exists is a stale decision, and a stale decision reads as a considered one.
 */
@SpringBootTest
class UserAccountMergeCoverageTest {

  @MockitoBean private RoleRepository roleRepository;

  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private DataSource dataSource;

  /**
   * Columns that reference {@code app_user} without a foreign key, and so are invisible to the
   * catalogue sweep below.
   *
   * <p>The two audit <em>target</em> columns are deliberately FK-less so the trail outlives the
   * account (REQ-AUDIT-001, stated in a {@code COMMENT ON COLUMN} by V235). They still have to be
   * classified — an audit row must not follow a member — so they are added to the swept set by hand
   * rather than left out of it.
   */
  private static final List<String> FK_LESS_BY_DESIGN =
      List.of("audit_event.target_user_id", "bank_audit_event.target_user_id");

  /**
   * Columns the sweep finds that are not a reference to a member at all.
   *
   * <p>{@code app_user.approved_by_id} <em>is</em> one and is classified as staying. This set is
   * for the opposite case and is currently empty; it exists so a future {@code
   * some_table.system_user_id} can be excluded with a reason rather than by being quietly dropped
   * into one of the two lists.
   */
  private static final Set<String> NOT_A_MEMBER_REFERENCE = Set.of();

  @Test
  @DisplayName("every column referencing a member is classified as following or staying")
  void everyUserReferencingColumnIsClassified() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    Set<String> inSchema =
        new LinkedHashSet<>(
            jdbc.queryForList(
                """
                SELECT src.relname || '.' || att.attname
                FROM pg_constraint con
                JOIN pg_class src ON src.oid = con.conrelid
                JOIN pg_class tgt ON tgt.oid = con.confrelid
                JOIN unnest(con.conkey) AS k(attnum) ON TRUE
                JOIN pg_attribute att ON att.attrelid = src.oid AND att.attnum = k.attnum
                WHERE con.contype = 'f' AND tgt.relname = 'app_user'
                ORDER BY 1
                """,
                String.class));
    inSchema.addAll(FK_LESS_BY_DESIGN);
    inSchema.removeAll(NOT_A_MEMBER_REFERENCE);

    assertThat(inSchema)
        .as("columns referencing app_user (a sweep matching none would pass vacuously)")
        .hasSizeGreaterThanOrEqualTo(40);

    Set<String> follows = new LinkedHashSet<>(UserAccountMergeService.followedColumns());
    Set<String> stays = new LinkedHashSet<>(UserAccountMergeService.STAYS_WITH_THE_ACT);

    Set<String> classified = new LinkedHashSet<>(follows);
    classified.addAll(stays);

    Set<String> unclassified = new LinkedHashSet<>(inSchema);
    unclassified.removeAll(classified);
    assertThat(unclassified)
        .as(
            """
            These columns reference a member and the account merge has no opinion about them, so \
            the merge would silently leave their rows on the emptied account. Decide which side \
            each belongs on and add it to FOLLOWS_THE_MEMBER (it says "this belongs to X") or to \
            STAYS_WITH_THE_ACT (it says "X did this, then"). Do not add it to whichever list is \
            shorter.\
            """)
        .isEmpty();

    Set<String> stale = new LinkedHashSet<>(classified);
    stale.removeAll(inSchema);
    assertThat(stale)
        .as(
            "classified columns that no longer exist in the schema — a stale decision reads as a"
                + " considered one")
        .isEmpty();

    Set<String> both = new LinkedHashSet<>(follows);
    both.retainAll(stays);
    assertThat(both).as("a column classified as both following and staying").isEmpty();
  }
}
