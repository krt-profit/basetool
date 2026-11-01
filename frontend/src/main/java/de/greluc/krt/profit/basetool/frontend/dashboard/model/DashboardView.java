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

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The dashboard as the home page renders it: both columns, the widgets the member may still add and
 * the state the edit script echoes (REQ-UI-034).
 *
 * @param loaded whether the stored dashboard could be read; a failed read shows the default and
 *     turns editing off
 * @param version the layout version the next layout write echoes
 * @param customised whether the member changed the default
 * @param wide the instances of the wide column in order
 * @param narrow the instances of the narrow column in order
 * @param catalogue the widgets the member may add, in catalogue order
 * @param mode the mode
 */
public record DashboardView(
    boolean loaded,
    long version,
    boolean customised,
    @NotNull List<Item> wide,
    @NotNull List<Item> narrow,
    @NotNull List<DashboardWidgetType> catalogue,
    @NotNull DashboardMode mode) {

  /** The most instances a dashboard holds; the backend enforces the same cap. */
  public static final int MAX_INSTANCES = 12;

  /**
   * The view of a dashboard that could not be read: nothing to show and editing off.
   *
   * @param mode the mode
   * @return the empty, not loaded view
   */
  public static @NotNull DashboardView notLoaded(@NotNull DashboardMode mode) {
    return new DashboardView(false, 0, false, List.of(), List.of(), List.of(), mode);
  }

  /**
   * Copies the lists so the record stays immutable.
   *
   * @param loaded whether the stored dashboard could be read
   * @param version the layout version
   * @param customised whether the member changed the default
   * @param wide the wide column
   * @param narrow the narrow column
   * @param catalogue the widgets the member may add
   * @param mode the mode
   */
  public DashboardView {
    wide = List.copyOf(wide);
    narrow = List.copyOf(narrow);
    catalogue = List.copyOf(catalogue);
  }

  /**
   * The cap, for the templates.
   *
   * @return {@link #MAX_INSTANCES}
   */
  public int max() {
    return MAX_INSTANCES;
  }

  /**
   * Whether no instance is shown.
   *
   * @return {@code true} iff both columns are empty
   */
  public boolean empty() {
    return wide.isEmpty() && narrow.isEmpty();
  }

  /**
   * Whether the cap is reached, so no further instance can be added.
   *
   * @return {@code true} iff {@link #MAX_INSTANCES} instances are shown
   */
  public boolean full() {
    return wide.size() + narrow.size() >= MAX_INSTANCES;
  }

  /**
   * One instance as the page renders it.
   *
   * @param widget the stored instance or default
   * @param available whether the active context offers the instance; an unavailable one renders
   *     greyed with a hint
   * @param unitName the name of the pinned unit, or {@code null} when it follows the context or is
   *     not known to the caller
   */
  public record Item(
      @NotNull DashboardWidgetResponse widget, boolean available, @Nullable String unitName) {

    /**
     * The key the reorder helper restores the focus by: the stored id, else the widget type.
     *
     * @return a value unique within one dashboard
     */
    public @NotNull String key() {
      UUID id = widget.id();
      return id != null ? id.toString() : "default-" + widget.type().slug();
    }

    /**
     * The stored id for the edit script.
     *
     * @return the id, or an empty string while the default is not stored
     */
    public @NotNull String storedId() {
      return widget.id() != null ? widget.id().toString() : "";
    }
  }
}
