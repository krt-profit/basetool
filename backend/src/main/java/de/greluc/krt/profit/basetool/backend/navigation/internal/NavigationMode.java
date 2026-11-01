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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/** A navigation mode whose order is stored apart (REQ-UI-032); its tag is the path segment. */
@Getter
@RequiredArgsConstructor
public enum NavigationMode {

  /** The member navigation. */
  MEMBER("member"),

  /** The administration navigation. */
  ADMIN("admin");

  private final String tag;

  /**
   * Resolves a mode from its path segment.
   *
   * @param tag the segment, e.g. {@code member}
   * @return the mode
   * @throws BadRequestException when the segment names no mode
   */
  public static @NotNull NavigationMode fromTag(@NotNull String tag) {
    return Arrays.stream(values())
        .filter(mode -> mode.tag.equals(tag))
        .findFirst()
        .orElseThrow(() -> new BadRequestException("Unknown navigation mode."));
  }
}
