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

package de.greluc.krt.profit.basetool.backend.platform.api;

import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A module's part of the admin person search (REQ-SEC-060): the text columns of its tables that can
 * name a person, and the ones it deliberately leaves out.
 *
 * <p>A source is a pure declaration. The search matches every declared {@link Target} case
 * insensitively in one statement; the coverage test holds the union of all sources against the live
 * schema, so a text column nobody searches or exempts fails the build.
 */
public interface PersonMentionSource {

  /** The bounded route keys the frontend turns into a link to the page that shows a hit. */
  enum LinkKind {

    /** A member's page. */
    MEMBER,

    /** A mission's detail page. */
    MISSION,

    /** An operation's detail page. */
    OPERATION,

    /** A job order's detail page. */
    JOB_ORDER,

    /** The warehouse. */
    INVENTORY,

    /** A bank account's detail page. */
    BANK,

    /** The material exchange. */
    MARKET,

    /** The org chart. */
    ORG,

    /** The audit viewer. */
    AUDIT
  }

  /**
   * One searchable column.
   *
   * @param area the bounded area label, rendered via {@code admin.personSearch.area.*}
   * @param table the physical table
   * @param column the text column to match against, case-insensitively
   * @param idColumn the column holding the row's identity, so the hit can be linked
   * @param linkKind the route key the frontend maps to a URL, or {@code null} when the row has no
   *     page of its own and the hit is informational only
   */
  record Target(
      @NotNull String area,
      @NotNull String table,
      @NotNull String column,
      @NotNull String idColumn,
      @Nullable LinkKind linkKind) {}

  /**
   * Returns the columns of this module's tables that the search matches.
   *
   * @return the targets, in any order
   */
  @NotNull
  @Unmodifiable
  List<Target> targets();

  /**
   * Returns the text columns of this module's tables that are deliberately not searched, as {@code
   * table.column}: codes, enum-like values and technical payloads.
   *
   * @return the exempt columns
   */
  default @NotNull @Unmodifiable Set<String> exemptColumns() {
    return Set.of();
  }

  /**
   * Returns the subset of {@link #exemptColumns()} that can still hold a name, such as a payload
   * that carries a handle; the data export scrubs these columns.
   *
   * @return the exempt columns that may hold a name
   */
  default @NotNull @Unmodifiable Set<String> exemptButMayHoldAName() {
    return Set.of();
  }

  /**
   * Returns the tables of this module whose every text column is exempt, such as synced catalogue
   * data that nobody types into.
   *
   * @return the exempt tables
   */
  default @NotNull @Unmodifiable Set<String> exemptTables() {
    return Set.of();
  }
}
