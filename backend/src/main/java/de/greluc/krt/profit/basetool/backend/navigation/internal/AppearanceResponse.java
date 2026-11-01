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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The „Darstellung" section of the caller.
 *
 * @param desktopMode the chosen desktop mode
 * @param shortcutsEnabled whether the navigation shortcuts Alt+0 to Alt+9 are on (REQ-UI-035)
 * @param startPageMember the entry key of the member mode's start page, {@code null} for the
 *     dashboard (REQ-UI-034)
 * @param startPageAdmin the entry key of the administration mode's start page, {@code null} for the
 *     dashboard
 * @param version the section's optimistic-lock version, echoed on the next write
 */
public record AppearanceResponse(
    @NotNull DesktopMode desktopMode,
    boolean shortcutsEnabled,
    @Nullable String startPageMember,
    @Nullable String startPageAdmin,
    long version) {

  /**
   * The appearance of a member who never chose: the drawer, the shortcuts on, no start page,
   * version 0.
   */
  public static final AppearanceResponse DEFAULT =
      new AppearanceResponse(DesktopMode.DRAWER, true, null, null, 0);
}
