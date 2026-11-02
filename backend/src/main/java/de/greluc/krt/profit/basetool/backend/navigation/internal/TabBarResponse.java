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

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The tab-bar section of one mode.
 *
 * @param slots always {@link NavigationLimits#TAB_BAR_SLOTS} entries; an entry key for a chosen
 *     slot and {@code null} for a slot that is filled automatically
 * @param version the section's optimistic-lock version, echoed on the next write
 */
public record TabBarResponse(@NotNull List<String> slots, long version) {

  /** The section of a member who never edited the bar: every slot automatic. */
  public static final TabBarResponse AUTOMATIC =
      new TabBarResponse(NavigationLimits.automaticSlots(), 0);
}
