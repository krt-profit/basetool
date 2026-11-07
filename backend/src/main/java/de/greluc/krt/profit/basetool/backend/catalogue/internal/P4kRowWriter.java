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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kRecord;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Writes the catalogue's values onto one matched row and logs each written field as a {@code
 * CHANGED} report row (REQ-P4K-014): the old value is read before it is overwritten, and a game
 * text is logged by its column only, never by its value. A preview logs the same rows and writes
 * nothing.
 */
@RequiredArgsConstructor
final class P4kRowWriter {

  /** The run. */
  @NotNull private final P4kImportContext context;

  /** The kind of the row. */
  @NotNull private final P4kKind kind;

  /** The record the values come from. */
  @NotNull private final P4kRecord record;

  /** Whether at least one field changes. */
  @Getter private boolean changed;

  /**
   * Fills a fact value where the row holds none.
   *
   * @param column the column written
   * @param current the row's value
   * @param incoming the catalogue's value
   * @param setter writes the value
   * @param <V> the value type
   */
  <V> void fill(
      @NotNull String column,
      @Nullable V current,
      @Nullable V incoming,
      @NotNull Consumer<V> setter) {
    fill(column, current, incoming, setter, String::valueOf);
  }

  /**
   * Fills a reference where the row holds none, logging it by a short label.
   *
   * @param column the column written
   * @param current the row's value
   * @param incoming the catalogue's value
   * @param setter writes the value
   * @param label renders a value as an identifier or short name
   * @param <V> the value type
   */
  <V> void fill(
      @NotNull String column,
      @Nullable V current,
      @Nullable V incoming,
      @NotNull Consumer<V> setter,
      @NotNull Function<V, String> label) {
    if (P4kFields.fillIfNull(current, incoming, setter, context.isApply())) {
      changed = true;
      context
          .getReport()
          .changed(kind, record, column, null, incoming == null ? null : label.apply(incoming));
    }
  }

  /**
   * Writes a value the P4K catalogue owns: replaces the row's value whenever the catalogue holds a
   * different one, and keeps it when the catalogue holds none.
   *
   * @param column the column written
   * @param current the row's value
   * @param incoming the catalogue's value
   * @param setter writes the value
   * @param <V> the value type
   */
  <V> void lead(
      @NotNull String column,
      @Nullable V current,
      @Nullable V incoming,
      @NotNull Consumer<V> setter) {
    if (incoming == null || incoming.equals(current)) {
      return;
    }
    if (context.isApply()) {
      setter.accept(incoming);
    }
    changed = true;
    context
        .getReport()
        .changed(
            kind,
            record,
            column,
            current == null ? null : String.valueOf(current),
            String.valueOf(incoming));
  }

  /**
   * Fills a game text where the row holds none; the report names the column only (REQ-P4K-033).
   *
   * @param column the column written
   * @param current the row's text
   * @param incoming the catalogue's text
   * @param setter writes the text
   */
  void fillGameText(
      @NotNull String column,
      @Nullable String current,
      @Nullable String incoming,
      @NotNull Consumer<String> setter) {
    if (P4kFields.fillIfNull(current, incoming, setter, context.isApply())) {
      changed = true;
      context.getReport().changed(kind, record, column, null, null);
    }
  }

  /**
   * Replaces the row's name key with the catalogue's usable one.
   *
   * @param current the row's name key
   * @param incoming the catalogue's raw localisation key
   * @param setter writes the name key
   */
  void nameKey(
      @Nullable String current, @Nullable String incoming, @NotNull Consumer<String> setter) {
    if (P4kFields.refreshNameKey(current, incoming, setter, context.isApply())) {
      changed = true;
      context
          .getReport()
          .changed(kind, record, "name_key", current, P4kFields.storedNameKey(incoming));
    }
  }
}
