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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** Rarity label of a Wiki commodity's {@code tier} field, ranked onto the stored tier 1 to 5. */
@Getter
@RequiredArgsConstructor
public enum ScWikiCommodityRarity {
  /** Wiki label {@code common}. */
  COMMON(1),
  /** Wiki label {@code uncommon}. */
  UNCOMMON(2),
  /** Wiki label {@code rare}. */
  RARE(3),
  /** Wiki label {@code epic}. */
  EPIC(4),
  /** Wiki label {@code legendary}. */
  LEGENDARY(5);

  /** Stored commodity tier, rising with rarity. */
  private final int tier;

  /**
   * Reads a Wiki rarity label.
   *
   * @param label source label, matched case-insensitively
   * @return the rarity, or {@code null} for an absent or unknown label so one new label never
   *     fails the commodity page
   */
  @JsonCreator
  @Nullable
  public static ScWikiCommodityRarity fromSource(@Nullable String label) {
    if (label == null) {
      return null;
    }
    String key = label.strip().toUpperCase(Locale.ROOT);
    for (ScWikiCommodityRarity rarity : values()) {
      if (rarity.name().equals(key)) {
        return rarity;
      }
    }
    return null;
  }
}
