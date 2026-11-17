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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A kind's rules for a stored row that a class-name or name step of {@link P4kRowMatcher} found for
 * a record of another GUID; the defaults cover no record and consider every row.
 *
 * @param <T> the row type
 */
public interface P4kMatchRules<T> {

  /**
   * Returns the default rules.
   *
   * @param <T> the row type
   * @return rules that cover nothing and refuse no row
   */
  @NotNull
  static <T> P4kMatchRules<T> none() {
    return new P4kMatchRules<>() {};
  }

  /**
   * Tells whether a found row already stands for the record of a GUID it does not hold, so the
   * record needs neither a row of its own nor a case (REQ-P4K-050).
   *
   * @param guid the record's GUID
   * @param row the found row
   * @return {@code true} when the row covers the record
   */
  default boolean covers(@NotNull UUID guid, @NotNull T row) {
    return false;
  }

  /**
   * Tells whether a found row may still stand for the record of a GUID; a refused row neither
   * matches nor opens a case (REQ-P4K-054).
   *
   * @param guid the record's GUID
   * @param row the found row
   * @return {@code true} when the row stays a candidate
   */
  default boolean isCandidate(@NotNull UUID guid, @NotNull T row) {
    return true;
  }

  /**
   * Returns the GUID of another record that a row's own facts name in place of the GUID it holds;
   * the row then takes that GUID, and the GUID it holds no longer finds it (REQ-P4K-055).
   *
   * @param row a stored row
   * @return the GUID the row follows, or {@code null} when it keeps the one it holds
   */
  @Nullable
  default UUID followedGuid(@NotNull T row) {
    return null;
  }
}
