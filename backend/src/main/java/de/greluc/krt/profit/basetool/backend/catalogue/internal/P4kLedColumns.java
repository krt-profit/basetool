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
 * The SC Wiki and UEX side of the columns the P4K import leads on one row (REQ-P4K-017,
 * REQ-P4K-024, REQ-P4K-031): the source writes a led column only while the P4K holds no value for
 * it, and a source value that differs from the kept one is collected as a disagreement for {@code
 * external_sync_report}; values agree by {@link P4kFields#agrees}.
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
   * Writes a source value into a led column whose P4K value is the row's own: a row without a P4K
   * GUID, or one whose column is still empty, takes the source value.
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
   * Writes a source value into a led column whose P4K value is the row's own, rendering a
   * disagreement with a label.
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
    compare(column, current, incoming, label);
  }

  /**
   * Writes a source value into a displayed column whose P4K value is kept in a column of its own:
   * while the P4K's value is {@code null} the source value is written, a {@code null} included.
   *
   * @param column the displayed column
   * @param p4kValue the P4K's own value of the column
   * @param incoming the source value
   * @param setter writes the displayed column
   * @param <V> the value type
   */
  <V> void fillGap(
      @NotNull String column,
      @Nullable V p4kValue,
      @Nullable V incoming,
      @NotNull Consumer<V> setter) {
    if (p4kValue == null) {
      setter.accept(incoming);
      return;
    }
    compare(column, p4kValue, incoming, String::valueOf);
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

  /**
   * Collects a source value that differs from the kept one.
   *
   * @param column the column
   * @param kept the value that stays
   * @param incoming the source value
   * @param label renders a value
   * @param <V> the value type
   */
  private <V> void compare(
      @NotNull String column,
      @NotNull V kept,
      @Nullable V incoming,
      @NotNull Function<V, String> label) {
    if (incoming != null && !P4kFields.agrees(kept, incoming)) {
      disagreements.add(P4kFields.disagreement(column, label.apply(kept), label.apply(incoming)));
    }
  }
}
