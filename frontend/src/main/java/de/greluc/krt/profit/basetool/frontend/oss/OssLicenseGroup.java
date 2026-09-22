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

package de.greluc.krt.profit.basetool.frontend.oss;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One licence section of the page: the licence and every shipped component offered under it.
 *
 * @param license the licence the section is headed by
 * @param components the components under it, sorted by name ignoring case, then by version
 */
public record OssLicenseGroup(
    @NotNull OssLicense license, @NotNull @Unmodifiable List<OssComponent> components) {

  /**
   * Copies the component list so a group handed to the view cannot be changed behind it.
   *
   * @param license see the record description
   * @param components see the record description
   */
  public OssLicenseGroup {
    components = List.copyOf(components);
  }
}
