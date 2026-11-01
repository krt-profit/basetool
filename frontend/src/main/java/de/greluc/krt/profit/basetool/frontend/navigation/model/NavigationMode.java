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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import java.util.Arrays;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The two navigation modes whose phone tab bars are stored per mode (REQ-UI-032); the segment names
 * the mode in the endpoints and in the page's data attributes.
 */
@Getter
@RequiredArgsConstructor
public enum NavigationMode {

  /** The member navigation. */
  MEMBER("member"),

  /** The administration navigation. */
  ADMIN("admin");

  private final String segment;

  /**
   * Resolves the mode a path segment names.
   *
   * @param segment the segment, e.g. {@code member}
   * @return the mode, or empty for an unknown segment
   */
  @NotNull
  public static Optional<NavigationMode> fromSegment(@NotNull String segment) {
    return Arrays.stream(values()).filter(mode -> mode.segment.equals(segment)).findFirst();
  }
}
