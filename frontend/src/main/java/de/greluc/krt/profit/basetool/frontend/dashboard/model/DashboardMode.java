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

package de.greluc.krt.profit.basetool.frontend.dashboard.model;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

/** The two navigation modes, each with its own dashboard (REQ-UI-034). */
public enum DashboardMode {

  /** The member mode. */
  MEMBER,

  /** The admin mode. */
  ADMIN;

  /**
   * Reads a mode from its path segment.
   *
   * @param slug the segment, e.g. {@code member}
   * @return the mode, empty for an unknown segment
   */
  public static @NotNull Optional<DashboardMode> fromSlug(@NotNull String slug) {
    return Stream.of(values()).filter(mode -> mode.slug().equals(slug)).findFirst();
  }

  /**
   * The mode's segment in the backend and frontend paths.
   *
   * @return the lower-case name
   */
  public @NotNull String slug() {
    return name().toLowerCase(Locale.ROOT);
  }
}
