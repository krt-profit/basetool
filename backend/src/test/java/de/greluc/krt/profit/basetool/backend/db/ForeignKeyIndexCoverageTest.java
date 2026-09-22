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

import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Turns REQ-DATA-017 ("every foreign key has a covering index") from a review rule into a gate: it
 * reads every foreign key of the Flyway-built schema from {@code pg_constraint} and fails for any
 * whose columns are not the <em>leading</em> columns of a usable index on the referencing table.
 *
 * <p>Why the leading columns: Postgres can only use a b-tree index for an equality lookup on its
 * leading key columns. An FK without one makes every {@code DELETE} / key {@code UPDATE} of the
 * referenced row scan the whole referencing table to check or cascade the constraint, and every
 * "children of this parent" read a sequential scan. A composite index whose first column is a
 * different one does not help — which is exactly how {@code job_order_assignees.user_id} and {@code
 * bank_posting.holder_id} escaped the earlier sweeps (V175). A partial index counts only when its
 * predicate is exactly {@code <fk column> IS NOT NULL} on a single-column key: every lookup the
 * constraint or a "children of" read makes is {@code fk = ?}, which implies that predicate, so
 * Postgres can use it (the V244 pattern). Any other predicate hides rows the check has to see.
 *
 * <p>Found on 2026-09-22 (BE-PERF-10): 38 uncovered foreign keys (the audit, reading the
 * migrations, had listed eleven), all indexed by V245. A new uncovered FK now fails this test in
 * the same PR that introduces it. An entry in {@link #ALLOWED_UNCOVERED} needs a written
 * justification next to it.
 */
@SpringBootTest
@ActiveProfiles("test")
class ForeignKeyIndexCoverageTest {

  /**
   * Foreign keys deliberately left without a covering index, as {@code table.constraint}. Empty:
   * every FK is covered. Add an entry only with a comment saying why the lookup can never be hot
   * (e.g. a single-row configuration table).
   */
  private static final Set<String> ALLOWED_UNCOVERED = Set.of();

  /**
   * Every public-schema foreign key whose column list is not, as a set, the leading columns of a
   * valid index on the referencing table that is either non-partial or partial on exactly {@code
   * <fk column> IS NOT NULL}. {@code indkey} is an {@code int2vector}; cast to {@code int2[]} it is
   * zero-based, hence the {@code [0:n-1]} slice.
   */
  private static final String UNCOVERED_FOREIGN_KEYS =
      """
      SELECT c.conrelid::regclass::text || '.' || c.conname
            || ' (' || (SELECT string_agg(a.attname, ', ' ORDER BY k.ord)
                        FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord)
                        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum)
            || ')'
      FROM pg_constraint c
      WHERE c.contype = 'f'
        AND c.connamespace = 'public'::regnamespace
        AND NOT EXISTS (
          SELECT 1 FROM pg_index i
          WHERE i.indrelid = c.conrelid
            AND i.indisvalid
            AND (i.indpred IS NULL
                 OR (cardinality(c.conkey) = 1
                     AND pg_get_expr(i.indpred, i.indrelid) =
                         '(' || (SELECT a.attname FROM pg_attribute a
                                 WHERE a.attrelid = c.conrelid AND a.attnum = c.conkey[1])
                             || ' IS NOT NULL)'))
            AND (i.indkey::int2[])[0:cardinality(c.conkey) - 1] @> c.conkey
            AND (i.indkey::int2[])[0:cardinality(c.conkey) - 1] <@ c.conkey)
      ORDER BY 1
      """;

  @Autowired private DataSource dataSource;

  @Test
  void everyForeignKeyHasALeadingIndex() {
    List<String> uncovered =
        new JdbcTemplate(dataSource)
            .queryForList(UNCOVERED_FOREIGN_KEYS, String.class).stream()
                .filter(fk -> !ALLOWED_UNCOVERED.contains(fk.substring(0, fk.indexOf(' '))))
                .toList();

    assertThat(uncovered)
        .as(
            "foreign keys without a covering (leading) index — add one in a Flyway"
                + " migration (REQ-DATA-017)")
        .isEmpty();
  }

  @Test
  void sweepSeesTheSchema_aKnownCoveredForeignKeyIsNotReported() {
    // A canary against a query that silently matches nothing: the schema has well over a hundred
    // foreign keys, and ship.owner_id (V34's idx_ship_owner_id) is one the sweep must consider.
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer foreignKeys =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_constraint WHERE contype = 'f'"
                + " AND connamespace = 'public'::regnamespace",
            Integer.class);
    assertThat(foreignKeys).isGreaterThan(100);
    Integer shipOwnerFks =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_constraint c JOIN pg_attribute a"
                + " ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)"
                + " WHERE c.contype = 'f' AND c.conrelid = 'ship'::regclass"
                + " AND a.attname = 'owner_id'",
            Integer.class);
    assertThat(shipOwnerFks).isEqualTo(1);
  }
}
