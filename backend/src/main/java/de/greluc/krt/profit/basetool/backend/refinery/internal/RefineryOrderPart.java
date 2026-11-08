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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import java.util.EnumSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** A part of a refinery order an update request writes (REQ-REFINERY-029). */
public enum RefineryOrderPart {
  /** The game-data section, checked against {@code gameDataVersion}. */
  GAME_DATA,
  /** The finances section, checked against {@code financesVersion}. */
  FINANCES,
  /** The mission link, which belongs to neither section and is not checked. */
  MISSION_LINK;

  /** Every part, written by a request that names none. */
  @Unmodifiable
  static final Set<RefineryOrderPart> ALL = Set.copyOf(EnumSet.allOf(RefineryOrderPart.class));

  /**
   * The counter section this part writes.
   *
   * @return the section, or {@code null} for the mission link
   */
  @Nullable
  RefineryOrderSection section() {
    return switch (this) {
      case GAME_DATA -> RefineryOrderSection.GAME_DATA;
      case FINANCES -> RefineryOrderSection.FINANCES;
      case MISSION_LINK -> null;
    };
  }
}
