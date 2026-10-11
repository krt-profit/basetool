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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A module's part of the Art. 15 / Art. 20 data export (REQ-SEC-058): the sections that select the
 * rows its module holds for one member.
 *
 * <p>A section is a declaration, a single read-only statement with the named parameter {@code
 * :userId}; the export runs it, scrubs the declared columns and orders the sections by {@link
 * Section#position()}. A statement selects only the member's own columns, never another member's id
 * or handle.
 */
public interface PersonalDataExportSection {

  /** The legal basis a section is exported under. */
  enum LegalBasis {

    /** Covered by the right of access only. */
    ART_15,

    /** Covered by access and portable under Art. 20. */
    ART_15_20
  }

  /**
   * One export section.
   *
   * @param position where the section sits in the export, ascending and unique across modules
   * @param key the stable machine key, also the JSON property name and the suffix of the {@code
   *     pdf.export.section.*} label key
   * @param legalBasis why the member may receive it
   * @param rationale one sentence on why the section has this legal basis; not serialised
   * @param sql the statement, selecting the member's rows through {@code :userId}
   * @param scrubbedColumns the selected columns passed through the handle scrubber
   * @param unscrubbedPersonColumns the selected person-name columns left as they are, each with the
   *     reason
   */
  record Section(
      int position,
      @NotNull String key,
      @NotNull LegalBasis legalBasis,
      @NotNull String rationale,
      @NotNull String sql,
      @NotNull @Unmodifiable Set<String> scrubbedColumns,
      @NotNull @Unmodifiable Map<String, String> unscrubbedPersonColumns) {

    /** Copies the column declarations so a section stays immutable. */
    public Section {
      scrubbedColumns = Set.copyOf(scrubbedColumns);
      unscrubbedPersonColumns = Map.copyOf(unscrubbedPersonColumns);
    }

    /**
     * Creates a section that scrubs nothing and exempts nothing.
     *
     * @param position where the section sits in the export
     * @param key the stable machine key
     * @param legalBasis why the member may receive it
     * @param rationale why the section has this legal basis
     * @param sql the statement
     */
    public Section(
        int position,
        @NotNull String key,
        @NotNull LegalBasis legalBasis,
        @NotNull String rationale,
        @NotNull String sql) {
      this(position, key, legalBasis, rationale, sql, Set.of(), Map.of());
    }

    /**
     * Returns this section with further columns scrubbed.
     *
     * @param columns the selected column aliases to pass through the handle scrubber
     * @return the extended section
     */
    public @NotNull Section scrubbing(@NotNull String... columns) {
      Set<String> all = new HashSet<>(scrubbedColumns);
      all.addAll(List.of(columns));
      return new Section(position, key, legalBasis, rationale, sql, all, unscrubbedPersonColumns);
    }

    /**
     * Returns this section with one person-name column left unscrubbed on purpose.
     *
     * @param column the selected column alias
     * @param reason why the value must stay as it is
     * @return the extended section
     */
    public @NotNull Section keeping(@NotNull String column, @NotNull String reason) {
      Map<String, String> all = new HashMap<>(unscrubbedPersonColumns);
      all.put(column, reason);
      return new Section(position, key, legalBasis, rationale, sql, scrubbedColumns, all);
    }

    /**
     * Tells whether a selected column is passed through the handle scrubber.
     *
     * @param column the column alias as the statement yields it
     * @return {@code true} when the value is scrubbed
     */
    public boolean scrubs(@NotNull String column) {
      return scrubbedColumns.contains(column);
    }
  }

  /**
   * Returns the sections this module contributes.
   *
   * @return the sections, in any order
   */
  @NotNull
  List<Section> sections();
}
