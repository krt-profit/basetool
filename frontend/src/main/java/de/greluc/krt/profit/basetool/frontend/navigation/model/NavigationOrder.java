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
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * The caller's stored group and link order as the navigation renders it (REQ-UI-032): one order and
 * one version per mode, applied to the permitted groups and links.
 *
 * @param member the member mode's order
 * @param admin the administration mode's order
 */
public record NavigationOrder(@NotNull Mode member, @NotNull Mode admin) {

  /** The scope that holds the order of a mode's groups; every other scope is a group key. */
  public static final String GROUPS_SCOPE = "groups";

  /** The order of a caller whose preferences were not read: the default order. */
  public static final NavigationOrder NOT_LOADED = new NavigationOrder(Mode.DEFAULT, Mode.DEFAULT);

  /**
   * Builds the view of a stored order section.
   *
   * @param stored the stored section
   * @return the order to render
   */
  @NotNull
  public static NavigationOrder of(@NotNull OrderResponse stored) {
    return new NavigationOrder(Mode.of(stored.member()), Mode.of(stored.admin()));
  }

  /**
   * One mode's stored order.
   *
   * @param scopes the stored keys by scope
   * @param version the order version the next write of the mode echoes
   */
  public record Mode(@NotNull Map<String, List<String>> scopes, long version) {

    /** The mode of a caller who moved nothing. */
    public static final Mode DEFAULT = new Mode(Map.of(), 0);

    @NotNull
    private static Mode of(@NotNull OrderModeResponse stored) {
      return new Mode(stored.scopes(), stored.version());
    }

    /**
     * The stored order of the mode's groups.
     *
     * @return the group keys, empty when the groups were never moved
     */
    @NotNull
    public List<String> groups() {
      return scopes.getOrDefault(GROUPS_SCOPE, List.of());
    }

    /**
     * The stored order of one group's links.
     *
     * @param groupKey the group key
     * @return the link keys, empty when the group's links were never moved
     */
    @NotNull
    public List<String> links(@NotNull String groupKey) {
      return scopes.getOrDefault(groupKey, List.of());
    }
  }
}
