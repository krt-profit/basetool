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

import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonSearchHitDto;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
import de.greluc.krt.profit.basetool.backend.support.PersonSearchTargets;
import de.greluc.krt.profit.basetool.backend.support.PersonSearchTargets.Target;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds every mention of a name across the application's free-text surfaces (REQ-SEC-060).
 *
 * <p><b>Why this exists.</b> A person's name can sit where no foreign key points: an external
 * mission participant, a party lead without an account, a job-order handover recipient, an
 * org-chart placeholder, a booking reason, an admin's note. An Art. 16 rectification or Art. 17
 * erasure request from such a person could not be served, because nothing could find the entries —
 * and a rectification that fixes one of four occurrences is not a rectification. This is the search
 * that makes those requests answerable, and {@code docs/privacy/data-subject-requests.md} tells the
 * reader to run it for <em>every</em> Art. 16/17 request, members included.
 *
 * <p><b>ADMIN only</b>, and not merely because it is expensive: a query that returns every place a
 * given name appears is a profile of that person assembled across the whole system.
 *
 * <p><b>Bounded by construction.</b> One statement, a {@code UNION ALL} over the registry with a
 * per-branch {@code LIMIT}, then an overall cap. A multi-table {@code ILIKE} sweep with no ceiling
 * is a denial-of-service waiting for a one-character search term, so:
 *
 * <ul>
 *   <li>the term must be at least {@value #MIN_TERM_LENGTH} characters
 *   <li>each branch returns at most {@value #PER_TARGET_LIMIT} rows
 *   <li>the whole result is capped at {@value #TOTAL_LIMIT}, and the caller is told when it was
 *       truncated rather than being left to assume it saw everything
 * </ul>
 *
 * <p><b>Case-insensitive, always.</b> Whoever typed the name was not copying it from a roster, so
 * matching case would miss the very entries this exists to find.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PersonSearchService {

  /** Shortest search term accepted; below this the result is noise rather than an answer. */
  public static final int MIN_TERM_LENGTH = 3;

  /** Rows returned per searched column. */
  public static final int PER_TARGET_LIMIT = 25;

  /** Rows returned in total. */
  public static final int TOTAL_LIMIT = 300;

  /** Characters of surrounding text kept per hit, so an admin can judge it without opening it. */
  private static final int SNIPPET_LENGTH = 200;

  /**
   * How many characters of the value precede the match in a snippet.
   *
   * <p>A quarter of the window, so the name appears near the start of what the admin reads while
   * still carrying the sentence it sits in -- which is what tells them whether the mention is the
   * person they mean.
   */
  private static final int SNIPPET_CONTEXT = 50;

  /**
   * Identifier shape the registry is allowed to contain. The table and column names are
   * interpolated into SQL — they cannot be bound as parameters — so they are validated against this
   * before a statement is built, even though they come from a compile-time constant list. Defence
   * in depth against a future edit that pastes something else into the registry.
   */
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

  private final AuditService auditService;

  @PersistenceContext private EntityManager entityManager;

  /**
   * The outcome of one search.
   *
   * @param hits the matches, in registry order (member record first, audit trails last)
   * @param truncated whether the overall cap was reached, so the admin knows the list is partial
   * @param cappedColumns the columns that hit the <b>per-column</b> cap, as {@code table.column}.
   *     <p>Reported separately because the two caps are reached independently and the overall one
   *     almost never fires: with 75 registered targets and 25 hits allowed per column, a name
   *     occurring 40 times in a single column produced 25 hits, a union total far below 300, and
   *     {@code truncated == false}. The admin read a complete-looking list while 15 occurrences
   *     were dropped — on the one surface whose whole purpose is to be exhaustive.
   */
  public record PersonSearchResult(
      List<PersonSearchHitDto> hits, boolean truncated, List<String> cappedColumns) {}

  /**
   * Searches every registered free-text column for the term, case-insensitively.
   *
   * @param term the name to look for; matched as a substring anywhere in the column
   * @return the hits and whether the result was truncated
   * @throws IllegalArgumentException when the term is shorter than {@link #MIN_TERM_LENGTH} after
   *     trimming
   */
  @Transactional(readOnly = true)
  public @NotNull PersonSearchResult search(@NotNull String term) {
    String trimmed = term.trim();
    if (trimmed.length() < MIN_TERM_LENGTH) {
      throw new IllegalArgumentException(
          "A person search needs at least " + MIN_TERM_LENGTH + " characters");
    }

    Query query = entityManager.createNativeQuery(buildSql());
    // The term is a bound parameter; only the identifiers are interpolated, and those are validated
    // against SAFE_IDENTIFIER while the SQL is assembled.
    // LikePatterns.contains is exactly "%" + escape(x) + "%", and it is what every other
    // substring search in this codebase already binds (BlueprintService, HangarService,
    // LocationService, MaterialService, MissionService, OperationService, UserService). A private
    // copy of the escape chain here would be one more place for the set of escaped characters to
    // drift from the rest.
    query.setParameter("term", LikePatterns.contains(trimmed));
    // The same term unwrapped, for `position` in the snippet window. Bound separately rather than
    // trimming the wildcards off in SQL: the escaped form is what ILIKE needs and the literal form
    // is what position needs, and deriving one from the other in SQL would be the kind of
    // cleverness
    // that stops being obviously correct.
    query.setParameter("term_plain", trimmed);
    // One past the overall cap, plus room for one probe row per target: the probes are consumed
    // while counting and never shown, so they must not push a real hit out of the window.
    query.setMaxResults(TOTAL_LIMIT + 1 + PersonSearchTargets.TARGETS.size());

    List<?> rows = query.getResultList();
    boolean truncated = rows.size() > TOTAL_LIMIT;
    List<PersonSearchHitDto> hits = new ArrayList<>();
    // Per column, because each branch asked for one row more than it is allowed to return: a
    // branch that comes back with PER_TARGET_LIMIT + 1 rows has more the admin is not being shown.
    Map<String, Integer> perColumn = new LinkedHashMap<>();
    Set<String> capped = new LinkedHashSet<>();

    for (Object row : rows.subList(0, Math.min(rows.size(), TOTAL_LIMIT))) {
      Object[] cells = (Object[]) row;
      String column = cells[1] + "." + cells[2];
      int seen = perColumn.merge(column, 1, Integer::sum);
      if (seen > PER_TARGET_LIMIT) {
        // The probe row. Not shown, and its only job is to prove there is more.
        capped.add(column);
        continue;
      }
      hits.add(
          new PersonSearchHitDto(
              (String) cells[0],
              (String) cells[1],
              (String) cells[2],
              cells[3] == null ? null : String.valueOf(cells[3]),
              (String) cells[4],
              (String) cells[5]));
    }
    log.info(
        "Person search returned {} hit(s) across {} column(s){}{}",
        hits.size(),
        PersonSearchTargets.TARGETS.size(),
        truncated ? " (truncated)" : "",
        capped.isEmpty() ? "" : " (" + capped.size() + " column(s) capped)");
    return new PersonSearchResult(hits, truncated, List.copyOf(capped));
  }

  /**
   * Appends the audit row for one performed search (REQ-SEC-060, REQ-AUDIT-001).
   *
   * <p>The search is a read, and a read would normally leave no trace — but this one assembles a
   * profile of a named person across the whole system, and is the one operation here whose misuse
   * would otherwise be invisible. Hence a row per search.
   *
   * <p>Separate from {@link #search(String)} rather than folded into it, because that method is
   * {@code readOnly = true}: Spring marks the JDBC connection read-only and Postgres refuses an
   * {@code INSERT} on it. The row gets its own short writable transaction, which is also what
   * satisfies the {@code MANDATORY} propagation on {@link AuditService#record} — a controller
   * calling it straight from a handler cannot satisfy it at all.
   *
   * <p><b>The term itself never reaches the row.</b> It is somebody's name, and REQ-AUDIT-001 keeps
   * user free text out of the details payload; its length and the hit count are enough to judge how
   * the surface was used.
   *
   * @param term the term that was searched, used only for its trimmed length
   * @param result what the search returned, used only for its hit count and truncation flag
   */
  @Transactional
  public void recordSearch(@NotNull String term, @NotNull PersonSearchResult result) {
    auditService.record(
        AuditEventType.PERSON_SEARCH_PERFORMED,
        null,
        null,
        null,
        AuditDetails.of("termLength", term.trim().length())
            .with("hits", result.hits().size())
            .with("truncated", result.truncated()));
  }

  /**
   * Assembles the {@code UNION ALL} over the registry.
   *
   * <p>One statement rather than one query per column: the planner sees the whole thing, the trip
   * to the database happens once, and a per-branch {@code LIMIT} keeps any single column from
   * dominating the result.
   *
   * @return the SQL, with {@code :term} left to be bound
   */
  private @NotNull String buildSql() {
    StringBuilder sql = new StringBuilder(PersonSearchTargets.TARGETS.size() * 220);
    boolean first = true;
    for (Target t : PersonSearchTargets.TARGETS) {
      requireSafeIdentifier(t.table());
      requireSafeIdentifier(t.column());
      requireSafeIdentifier(t.idColumn());
      if (!first) {
        sql.append(" UNION ALL ");
      }
      first = false;
      sql.append("(SELECT ")
          .append(quote(t.area()))
          .append(" AS area, ")
          .append(quote(t.table()))
          .append(" AS src_table, ")
          .append(quote(t.column()))
          .append(" AS src_column, ")
          .append("CAST(")
          .append(t.idColumn())
          .append(" AS text) AS row_id, ")
          // A window around the match rather than the value's first 200 characters. A prefix
          // routinely did not contain the name the admin searched for -- the one part of a long
          // note they need to see to decide whether the hit is the person they mean -- so they had
          // to open the row to find out. `position` is computed on the lower-cased pair, matching
          // the ILIKE, and greatest(1, ...) keeps substring's one-based start legal when the match
          // sits near the beginning.
          .append("substring(")
          .append(t.column())
          .append(", greatest(1, position(lower(:term_plain) in lower(")
          .append(t.column())
          .append(")) - ")
          .append(SNIPPET_CONTEXT)
          .append("), ")
          .append(SNIPPET_LENGTH)
          .append(") AS snippet, ")
          .append(t.linkKind() == null ? "CAST(NULL AS text)" : quote(t.linkKind()))
          .append(" AS link_kind")
          .append(" FROM ")
          .append(t.table())
          .append(" WHERE ")
          .append(t.column())
          .append(" ILIKE :term")
          // Deterministic inside the branch, and one row past the cap. Without the ORDER BY it was
          // unspecified WHICH 25 of 40 matches came back -- so the same search could return
          // different rows on two runs, on a surface an admin uses to decide whether a name still
          // appears anywhere. Without the extra row the cap was invisible: 25 hits look identical
          // whether there were 25 matches or 400.
          .append(" ORDER BY ")
          .append(t.idColumn())
          .append(" LIMIT ")
          .append(PER_TARGET_LIMIT + 1)
          .append(')');
    }
    // A deterministic order for the outer LIMIT. Without one, which hits survive the 300-row cap
    // is whatever order the Append node happens to produce -- stable enough under a serial plan to
    // look reliable, and not stable under a Parallel Append. An admin re-running the same search
    // and seeing a different 300 rows has no way to tell a plan change from a data change, on a
    // surface whose whole purpose is to be complete. The order is also the one the page groups by.
    sql.append(" ORDER BY area, src_table, src_column, row_id");
    return sql.toString();
  }

  /**
   * Rejects an identifier the registry should never contain.
   *
   * @param identifier a table or column name from the registry
   * @throws IllegalStateException when it is not a plain lower-case SQL identifier
   */
  private static void requireSafeIdentifier(@NotNull String identifier) {
    if (!SAFE_IDENTIFIER.matcher(identifier).matches()) {
      throw new IllegalStateException(
          "PersonSearchTargets contains an identifier that is not a plain SQL name: " + identifier);
    }
  }

  /**
   * Renders a registry constant as a SQL string literal.
   *
   * <p>These are compile-time constants from the registry, not input, and {@link
   * #requireSafeIdentifier} has already rejected anything unexpected for the identifiers. The
   * doubling is here so an area or link-kind label containing an apostrophe could not break the
   * statement either.
   *
   * @param value the literal
   * @return the quoted literal
   */
  private static @NotNull String quote(@NotNull String value) {
    return "'" + value.replace("'", "''") + "'";
  }
}
