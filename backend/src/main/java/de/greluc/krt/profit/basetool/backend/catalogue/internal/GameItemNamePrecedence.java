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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * Decides which source writes a game item's canonical {@code name}: the P4K import on every row
 * with a {@code p4k_name} ({@link P4kItemValues#leadOnto}), the SC Wiki on rows without one, UEX
 * only on rows neither names (REQ-P4K-017).
 */
public final class GameItemNamePrecedence {

  private GameItemNamePrecedence() {}

  /**
   * Writes the Wiki display name unless the row carries a P4K name.
   *
   * @param item managed catalogue item
   * @param wikiName display name of the Wiki payload
   */
  static void applyWikiName(@NotNull GameItem item, @Nullable String wikiName) {
    if (item.getP4kName() == null && isUsableWikiName(wikiName)) {
      item.setName(wikiName.strip());
    }
  }

  /**
   * Writes the UEX display name only on a row without a name, or one neither the P4K import nor a
   * live Wiki record names.
   *
   * @param item managed catalogue item
   * @param uexName display name of the UEX payload
   */
  static void applyUexName(@NotNull GameItem item, @Nullable String uexName) {
    if (!StringUtils.hasText(uexName)) {
      return;
    }
    if (!StringUtils.hasText(item.getName()) || !isNamedByP4kOrWiki(item)) {
      item.setName(uexName.strip());
    }
  }

  /**
   * Tells whether a Wiki name can serve as a display name: not blank and free of markup.
   *
   * @param wikiName display name of a Wiki payload
   * @return whether the name is usable
   */
  @Contract("null -> false")
  static boolean isUsableWikiName(@Nullable String wikiName) {
    return StringUtils.hasText(wikiName) && !wikiName.contains("<") && !wikiName.contains(">");
  }

  private static boolean isNamedByP4kOrWiki(@NotNull GameItem item) {
    return item.getP4kName() != null
        || (item.getScwikiDeletedAt() == null && isUsableWikiName(item.getScwikiName()));
  }
}
