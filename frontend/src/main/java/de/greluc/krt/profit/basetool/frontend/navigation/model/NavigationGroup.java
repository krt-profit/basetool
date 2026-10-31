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

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A group of navigation entries, rendered as one drawer group or one list of a zone.
 *
 * @param key the stable group key ({@code data-group-key}, {@code krt.sidebar.<key>})
 * @param labelKey the message key of the heading, which is also the quick-access breadcrumb
 * @param icon the sprite icon name of the heading, or {@code null} for a zone without one
 * @param gate the condition under which any of the group's entries renders
 * @param entries the entries in their default order
 */
public record NavigationGroup(
    @NotNull String key,
    @NotNull String labelKey,
    @Nullable String icon,
    @NotNull NavigationGate gate,
    @NotNull List<NavigationEntry> entries) {

  /**
   * Copies the entries so the group stays immutable.
   *
   * @param key the stable group key
   * @param labelKey the message key of the heading
   * @param icon the heading icon, or {@code null}
   * @param gate the group's condition
   * @param entries the entries in their default order
   */
  public NavigationGroup {
    entries = List.copyOf(entries);
  }

  /**
   * An ungated group.
   *
   * @param key the stable group key
   * @param labelKey the message key of the heading
   * @param icon the heading icon, or {@code null}
   * @param entries the entries in their default order
   * @return the group
   */
  @NotNull
  public static NavigationGroup of(
      @NotNull String key,
      @NotNull String labelKey,
      @Nullable String icon,
      @NotNull NavigationEntry... entries) {
    return new NavigationGroup(key, labelKey, icon, NavigationGate.ALWAYS, List.of(entries));
  }

  /**
   * Whether a caller passes both this group's gate and an entry's own.
   *
   * @param entry an entry of this group
   * @param access the caller's reachable roles and flags
   * @return {@code true} iff the entry renders for the caller
   */
  public boolean permits(@NotNull NavigationEntry entry, @NotNull NavigationAccess access) {
    return gate.permits(access) && entry.gate().permits(access);
  }

  /**
   * This group under a condition.
   *
   * @param condition the group's gate
   * @return the changed group
   */
  @NotNull
  public NavigationGroup gate(@NotNull NavigationGate condition) {
    return new NavigationGroup(key, labelKey, icon, condition, entries);
  }
}
