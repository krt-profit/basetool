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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import de.greluc.krt.profit.basetool.backend.kernel.Quality;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A frozen quality catalogue used to resolve every requirement of one prepared action. */
public record QualityTierSelection(@NotNull List<Tier> tiers) {
  /** Copies the catalogue so later reads cannot replace a prepared action's quality facts. */
  public QualityTierSelection {
    tiers = List.copyOf(tiers);
  }

  /** The stable quality identity, selection rules and labels of one catalogue tier. */
  public record Tier(
      @NotNull UUID id,
      @NotNull String code,
      int minQuality,
      @NotNull String labelDe,
      @NotNull String labelEn,
      int sortOrder,
      boolean active,
      @NotNull Long version) {}

  /**
   * Resolves a requirement's explicit code, floor or base tier against this catalogue.
   *
   * @param code the optional case-insensitive tier code
   * @param minQuality the optional exact catalogue floor
   * @param keptTierIds inactive tiers already used by the edited requirement
   * @return the selected tier
   * @throws IllegalArgumentException when the named tier is unknown or inactive and not kept
   */
  @NotNull
  public Tier requirement(
      @Nullable String code, @Nullable Integer minQuality, @NotNull Collection<UUID> keptTierIds) {
    Tier tier;
    if (code != null && !code.isBlank()) {
      String normalized = code.trim().toUpperCase(Locale.ROOT);
      tier =
          tiers.stream()
              .filter(t -> t.code().equals(normalized))
              .findFirst()
              .orElseThrow(
                  () -> new IllegalArgumentException("Unknown quality tier code: " + code));
    } else if (minQuality != null) {
      tier =
          tiers.stream()
              .filter(t -> t.minQuality() == minQuality)
              .findFirst()
              .orElseThrow(() ->
                  new IllegalArgumentException("No quality tier has the floor " + minQuality));
    } else {
      tier = base();
    }
    if (!tier.active() && !keptTierIds.contains(tier.id())) {
      throw new IllegalArgumentException("Quality tier " + tier.code() + " is inactive");
    }
    return tier;
  }

  /**
   * Picks the highest active floor the ingredient's minimum quality reaches.
   *
   * @param ingredientMinQuality the ingredient's minimum quality, or null for no floor
   * @return the selected default tier
   */
  @NotNull
  public Tier defaultForIngredient(@Nullable Integer ingredientMinQuality) {
    int quality = Quality.orMin(ingredientMinQuality);
    return tiers.stream()
        .filter(Tier::active)
        .filter(t -> t.minQuality() <= quality)
        .max(Comparator.comparingInt(Tier::minQuality))
        .orElseGet(this::base);
  }

  @NotNull
  private Tier base() {
    return tiers.stream()
        .filter(t -> t.minQuality() == Quality.MIN)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("quality_tier has no tier with floor 0"));
  }
}
