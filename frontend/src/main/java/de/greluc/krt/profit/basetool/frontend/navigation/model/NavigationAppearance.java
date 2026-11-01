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
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * The caller's appearance as the navigation renders it (REQ-UI-026, REQ-UI-032): the stored desktop
 * mode and the version the next write echoes.
 *
 * @param loaded whether the stored appearance could be read; a failed read renders the drawer and
 *     disables the switch
 * @param mode the stored desktop mode
 * @param version the appearance section version the next write echoes
 */
public record NavigationAppearance(boolean loaded, @NotNull DesktopMode mode, long version) {

  /** The appearance of a caller whose preferences were not read: the drawer, and the switch off. */
  public static final NavigationAppearance NOT_LOADED =
      new NavigationAppearance(false, DesktopMode.DRAWER, 0);

  /**
   * Wraps a stored section.
   *
   * @param stored the stored appearance
   * @return the appearance to render
   */
  @NotNull
  public static NavigationAppearance of(@NotNull AppearanceResponse stored) {
    return new NavigationAppearance(true, stored.desktopMode(), stored.version());
  }

  /**
   * The options of the three-state switch, in display order, as the segmented control reads them.
   *
   * @return one map of {@code value} and {@code labelKey} per desktop mode
   */
  @NotNull
  public List<Map<String, String>> options() {
    return Arrays.stream(DesktopMode.values())
        .map(m -> Map.of("value", m.attribute(), "labelKey", m.labelKey()))
        .toList();
  }

  /**
   * The {@code data-nav-mode} attribute value of the page.
   *
   * @return the lower-case mode name
   */
  @NotNull
  public String modeAttribute() {
    return mode.attribute();
  }

  /**
   * Whether a mode is the stored one, for the pressed state of the switch.
   *
   * @param candidate the mode of one switch button
   * @return {@code true} iff it is the stored mode
   */
  public boolean is(@NotNull DesktopMode candidate) {
    return mode == candidate;
  }
}
