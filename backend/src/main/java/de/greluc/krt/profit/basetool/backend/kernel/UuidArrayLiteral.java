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

package de.greluc.krt.profit.basetool.backend.kernel;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * Renders UUIDs as one PostgreSQL array literal ({@code {a,NULL,b}}), the text a native statement
 * casts to {@code uuid[]}, so a statement over many rows binds one parameter however many rows it
 * names.
 */
public final class UuidArrayLiteral {

  /** Non-instantiable holder of a static helper. */
  private UuidArrayLiteral() {}

  /**
   * Renders UUIDs in their order; a {@code null} element renders as SQL {@code NULL}, an empty
   * collection as {@code {}}.
   *
   * @param uuids the UUIDs
   * @return the array literal
   */
  @NotNull
  public static String of(@NotNull Collection<UUID> uuids) {
    return uuids.stream()
        .map(uuid -> Objects.toString(uuid, "NULL"))
        .collect(Collectors.joining(",", "{", "}"));
  }
}
