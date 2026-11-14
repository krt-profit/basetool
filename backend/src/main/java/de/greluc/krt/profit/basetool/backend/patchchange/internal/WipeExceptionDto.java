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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A wipe exception with the name of its catalogue entry, as the admin page shows it
 * (REQ-PATCH-011).
 *
 * @param target the catalogue the entry lives in
 * @param id the entry's id
 * @param mode whether its stock is kept or wiped
 * @param name the entry's name, or {@code null} when the entry no longer exists
 */
public record WipeExceptionDto(
    @NotNull WipeExceptionTarget target,
    @NotNull UUID id,
    @NotNull WipeExceptionMode mode,
    @Nullable String name) {

  /**
   * Names an exception.
   *
   * @param exception the exception
   * @param name its entry's name, or {@code null}
   * @return the named exception
   */
  static @NotNull WipeExceptionDto of(@NotNull WipeException exception, @Nullable String name) {
    return new WipeExceptionDto(exception.target(), exception.id(), exception.mode(), name);
  }
}
