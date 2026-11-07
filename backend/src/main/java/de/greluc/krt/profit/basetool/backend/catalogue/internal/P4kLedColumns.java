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

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The SC Wiki and UEX side of the columns the P4K import leads on one row (REQ-P4K-031): a row
 * without a P4K GUID, or one whose column is still empty, takes the source value; on a P4K-known
 * row a stored value stays, and a source value that differs is collected as a disagreement for
 * {@code external_sync_report}.
 */
final class P4kLedColumns {

  /** The row written. */
  @NotNull private final P4kKeyedRow row;

  /** The disagreements found so far, one {@code column=…;stored=…;source=…} entry each. */
  @NotNull private final List<String> disagreements = new ArrayList<>();

  /**
   * Starts the writes of one row.
   *
   * @param row the row written
   */
  P4kLedColumns(@NotNull P4kKeyedRow row) {
    this.row = row;
  }

  /**
   * Writes a source value into a led column.
   *
   * @param column the column
   * @param current the row's value
   * @param incoming the source value; on a row the P4K does not know, {@code null} clears
   * @param setter writes the value
   * @param <V> the value type
   */
  <V> void write(
      @NotNull String column,
      @Nullable V current,
      @Nullable V incoming,
      @NotNull Consumer<V> setter) {
    write(column, current, incoming, setter, String::valueOf);
  }

  /**
   * Writes a source value into a led column, rendering a disagreement with a label.
   *
   * @param column the column
   * @param current the row's value
   * @param incoming the source value; on a row the P4K does not know, {@code null} clears
   * @param setter writes the value
   * @param label renders a value as an identifier or short name
   * @param <V> the value type
   */
  <V> void write(
      @NotNull String column,
      @Nullable V current,
      @Nullable V incoming,
      @NotNull Consumer<V> setter,
      @NotNull Function<V, String> label) {
    if (row.getP4kUuid() == null || current == null) {
      setter.accept(incoming);
      return;
    }
    if (incoming != null && !P4kFields.sameValue(current, incoming)) {
      disagreements.add(
          "column="
              + column
              + ";stored="
              + label.apply(current)
              + ";source="
              + label.apply(incoming));
    }
  }

  /**
   * Returns the disagreements found on the row.
   *
   * @return one entry per led column whose source value differs from the kept P4K value
   */
  @NotNull
  @Unmodifiable
  List<String> disagreements() {
    return List.copyOf(disagreements);
  }
}
