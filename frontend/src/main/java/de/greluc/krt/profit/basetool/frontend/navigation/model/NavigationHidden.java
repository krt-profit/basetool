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
 * The caller's hidden pages as the navigation renders them (REQ-UI-032): one list for both modes,
 * each mode rendering its own pages in the „Mehr“ area.
 *
 * @param loaded whether the stored pages could be read; a failed read hides nothing
 * @param version the hidden version the next write echoes
 * @param keys every stored hidden page key, including those no mode renders
 * @param member the hidden pages rendered in member mode, in registry order
 * @param admin the hidden pages rendered in admin mode, in registry order
 */
public record NavigationHidden(
    boolean loaded,
    long version,
    @NotNull List<String> keys,
    @NotNull List<Item> member,
    @NotNull List<Item> admin) {

  /** The hidden pages of a caller whose preferences were not read: none. */
  public static final NavigationHidden NOT_LOADED =
      new NavigationHidden(false, 0, List.of(), List.of(), List.of());

  /**
   * Copies the lists so the record stays immutable.
   *
   * @param loaded whether the stored pages could be read
   * @param version the hidden version
   * @param keys every stored hidden page key
   * @param member the hidden pages rendered in member mode
   * @param admin the hidden pages rendered in admin mode
   */
  public NavigationHidden {
    keys = List.copyOf(keys);
    member = List.copyOf(member);
    admin = List.copyOf(admin);
  }

  /**
   * Whether a page is hidden.
   *
   * @param entryKey the entry key
   * @return {@code true} iff the key is stored
   */
  public boolean isHidden(@NotNull String entryKey) {
    return keys.contains(entryKey);
  }

  /**
   * The stored keys space-separated, for the script that edits them.
   *
   * @return the keys
   */
  @NotNull
  public String keysAttribute() {
    return String.join(" ", keys);
  }

  /**
   * One hidden page as the „Mehr“ area renders it.
   *
   * @param entry the registry entry
   * @param available whether the active context offers the page; an unavailable one renders greyed
   */
  public record Item(@NotNull NavigationEntry entry, boolean available) {}
}
