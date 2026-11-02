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

/**
 * The caller's favourites as the navigation renders them (REQ-UI-032): one list for both modes,
 * split by the mode each entry belongs to, sharing one cap.
 *
 * @param loaded whether the stored favourites could be read; a failed read disables the stars
 * @param version the favourites section version the next write echoes
 * @param keys every stored favourite key in order, including those no mode renders
 * @param member the favourites rendered in member mode, in order
 * @param admin the favourites rendered in admin mode, in order
 */
public record NavigationFavourites(
    boolean loaded,
    long version,
    @NotNull List<String> keys,
    @NotNull List<Item> member,
    @NotNull List<Item> admin) {

  /** The most favourites a member keeps; the backend enforces the same cap. */
  public static final int MAX = 12;

  /** The favourites of a caller whose preferences were not read: none, and the stars off. */
  public static final NavigationFavourites NOT_LOADED =
      new NavigationFavourites(false, 0, List.of(), List.of(), List.of());

  /**
   * Copies the lists so the record stays immutable.
   *
   * @param loaded whether the stored favourites could be read
   * @param version the favourites section version
   * @param keys every stored favourite key in order
   * @param member the favourites rendered in member mode
   * @param admin the favourites rendered in admin mode
   */
  public NavigationFavourites {
    keys = List.copyOf(keys);
    member = List.copyOf(member);
    admin = List.copyOf(admin);
  }

  /**
   * Whether an entry is one of the stored favourites.
   *
   * @param entryKey the entry key
   * @return {@code true} iff the key is stored
   */
  public boolean isFavourite(@NotNull String entryKey) {
    return keys.contains(entryKey);
  }

  /**
   * The cap, for the templates.
   *
   * @return {@link #MAX}
   */
  public int max() {
    return MAX;
  }

  /**
   * Whether the cap is reached, so no further entry can be added.
   *
   * @return {@code true} iff {@link #MAX} favourites are stored
   */
  public boolean full() {
    return keys.size() >= MAX;
  }

  /**
   * The stored keys space-separated, for the script that toggles one.
   *
   * @return the keys in order
   */
  @NotNull
  public String keysAttribute() {
    return String.join(" ", keys);
  }

  /**
   * One favourite as the navigation renders it.
   *
   * @param entry the registry entry
   * @param available whether the active context offers the entry; an unavailable one renders greyed
   */
  public record Item(@NotNull NavigationEntry entry, boolean available) {}
}
