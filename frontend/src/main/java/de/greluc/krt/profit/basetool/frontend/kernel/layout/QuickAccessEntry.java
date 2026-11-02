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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One page the quick access offers besides the menu, named by message keys (REQ-UI-026).
 *
 * @param href the page's path, starting with {@code /}
 * @param labelKey the message key of its name
 * @param crumbKey the message key of the group it is shown under
 * @param icon the sprite symbol without its {@code krt-icon-} prefix
 * @param keywordsKey the message key of its additional search terms
 * @param pageKey the entry key of the menu page this entry is a tab of, so that hiding the page
 *     hides the entry; {@code null} for an entry that belongs to no hideable page
 * @param entryKey the stable entry key of the registered tab, which lets the quick access star the
 *     entry and restore it as a start page; {@code null} for an entry without a registered tab
 */
public record QuickAccessEntry(
    @NotNull String href,
    @NotNull String labelKey,
    @NotNull String crumbKey,
    @NotNull String icon,
    @NotNull String keywordsKey,
    @Nullable String pageKey,
    @Nullable String entryKey) {

  /**
   * An entry that belongs to no hideable page.
   *
   * @param href the page's path, starting with {@code /}
   * @param labelKey the message key of its name
   * @param crumbKey the message key of the group it is shown under
   * @param icon the sprite symbol without its {@code krt-icon-} prefix
   * @param keywordsKey the message key of its additional search terms
   */
  public QuickAccessEntry(
      @NotNull String href,
      @NotNull String labelKey,
      @NotNull String crumbKey,
      @NotNull String icon,
      @NotNull String keywordsKey) {
    this(href, labelKey, crumbKey, icon, keywordsKey, null, null);
  }

  /**
   * An entry that is a registered tab of a hideable page.
   *
   * @param href the page's path, starting with {@code /}
   * @param labelKey the message key of its name
   * @param crumbKey the message key of the group it is shown under
   * @param icon the sprite symbol without its {@code krt-icon-} prefix
   * @param keywordsKey the message key of its additional search terms
   * @param pageKey the entry key of the menu page this entry is a tab of
   * @param entryKey the stable entry key of the tab
   * @return the entry
   */
  public static @NotNull QuickAccessEntry tab(
      @NotNull String href,
      @NotNull String labelKey,
      @NotNull String crumbKey,
      @NotNull String icon,
      @NotNull String keywordsKey,
      @NotNull String pageKey,
      @NotNull String entryKey) {
    return new QuickAccessEntry(href, labelKey, crumbKey, icon, keywordsKey, pageKey, entryKey);
  }
}
