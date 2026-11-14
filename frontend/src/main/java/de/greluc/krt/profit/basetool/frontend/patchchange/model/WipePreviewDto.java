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

package de.greluc.krt.profit.basetool.frontend.patchchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;

/**
 * Mirror of what each wipe group and each exception would touch now, selected or not
 * (REQ-PATCH-016).
 *
 * @param groups one entry per wipe group, never {@code null} once constructed
 * @param exceptions one entry per exception, never {@code null} once constructed
 */
@DtoMirror
public record WipePreviewDto(List<GroupCount> groups, List<ExceptionCount> exceptions) {

  /** Copies the lists, so a missing list reads as none. */
  public WipePreviewDto {
    groups = groups == null ? List.of() : List.copyOf(groups);
    exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
  }

  /**
   * Mirror of what one wipe group would touch.
   *
   * @param group the wipe group
   * @param rows the stock rows, or the ships of the in-game ships
   * @param members the members who hold them
   */
  public record GroupCount(WipeGroup group, long rows, long members) {}

  /**
   * Mirror of what one exception would keep or wipe.
   *
   * @param exception the named exception
   * @param rows the stock rows of its entry
   * @param members the members who hold them
   */
  public record ExceptionCount(WipeExceptionDto exception, long rows, long members) {}
}
