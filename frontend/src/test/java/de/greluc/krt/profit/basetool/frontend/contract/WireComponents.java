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

package de.greluc.krt.profit.basetool.frontend.contract;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The record components a DTO mirror puts on the wire, so the DTO contract tests compare what the
 * backend reads rather than the Java shape: a component marked {@link JsonUnwrapped} stands for the
 * components of the record it unwraps.
 */
final class WireComponents {

  private WireComponents() {}

  /**
   * Lists a record's wire components in declaration order, each unwrapped component replaced by the
   * wire components of its own record.
   *
   * @param record the record class
   * @return the components whose names the record's JSON carries
   * @throws IllegalArgumentException when an unwrapped component's type is not a record
   */
  static @NotNull @Unmodifiable List<RecordComponent> of(@NotNull Class<?> record) {
    List<RecordComponent> wire = new ArrayList<>();
    for (RecordComponent component : record.getRecordComponents()) {
      if (!component.getAccessor().isAnnotationPresent(JsonUnwrapped.class)) {
        wire.add(component);
      } else if (component.getType().isRecord()) {
        wire.addAll(of(component.getType()));
      } else {
        throw new IllegalArgumentException(
            record.getSimpleName()
                + "."
                + component.getName()
                + " unwraps "
                + component.getType().getName()
                + ", which is no record");
      }
    }
    return List.copyOf(wire);
  }
}
