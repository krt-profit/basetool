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

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What each wipe group and each exception would touch now, selected or not (REQ-PATCH-016).
 *
 * @param groups one entry per wipe group, in the groups' declaration order
 * @param exceptions one entry per exception, ordered by name
 */
public record WipePreviewDto(
    @NotNull @Unmodifiable List<GroupCount> groups,
    @NotNull @Unmodifiable List<ExceptionCount> exceptions) {

  /**
   * What one wipe group would touch: stock rows not named by an exception, or in-game ships.
   *
   * @param group the wipe group
   * @param rows the stock rows, or the ships of {@link WipeGroup#IN_GAME_SHIPS}
   * @param members the members who hold them
   */
  @Schema(name = "PatchWipeGroupCount")
  public record GroupCount(@NotNull WipeGroup group, long rows, long members) {}

  /**
   * What one exception would keep or wipe.
   *
   * @param exception the named exception
   * @param rows the stock rows of its entry
   * @param members the members who hold them
   */
  @Schema(name = "PatchWipeExceptionCount")
  public record ExceptionCount(@NotNull WipeExceptionDto exception, long rows, long members) {}
}
