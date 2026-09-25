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

package de.greluc.krt.profit.basetool.backend.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Curated equivalences between blueprint variant family keys that {@link
 * BlueprintVariantFamilyResolver} cannot merge structurally, such as base-name spelling drift or
 * unquoted sub-models.
 *
 * <p>Maps each alias family key to its canonical family key; an unrecognised key passes through
 * unchanged.
 */
@Component
public class BlueprintVariantAliasOverrides {

  /**
   * Alias family key → canonical family key. Both sides are already in {@link
   * BlueprintVariantFamilyResolver} family-key form (normalized, lowercased, quote-stripped).
   * Immutable after construction.
   */
  private final Map<String, String> canonicalByAlias;

  /**
   * Seeds the curated alias map. Every entry is keyed and valued in family-key form (the
   * lowercased, whitespace-collapsed, quote-stripped string the resolver produces) so the lookup is
   * a direct equality test with no further normalization. Entries fire only on an exact match, so
   * an entry whose alias key is absent from the live catalogue is a harmless no-op.
   */
  public BlueprintVariantAliasOverrides() {
    Map<String, String> map = new LinkedHashMap<>();

    register(map, "pulse pistol", "pulse laser pistol");

    register(map, "salvo esteban frag pistol", "salvo frag pistol");
    register(map, "salvo saeed frag pistol", "salvo frag pistol");
    register(map, "model ii arclight", "arclight pistol");
    register(map, "arclight model ii", "arclight pistol");

    this.canonicalByAlias = Map.copyOf(map);
  }

  /**
   * Returns the canonical family key for a registered alias, otherwise the key unchanged.
   *
   * @param familyKey a structural family key (normalized, quote-stripped), or {@code null}
   * @return the canonical family key for a registered alias, else {@code familyKey} unchanged
   */
  @Nullable
  @Contract("null -> null; !null -> !null")
  public String canonical(String familyKey) {
    if (familyKey == null) {
      return null;
    }
    return canonicalByAlias.getOrDefault(familyKey, familyKey);
  }

  /**
   * Registers one alias to canonical mapping in family-key form.
   *
   * @param map the map being seeded
   * @param aliasFamilyKey the structural family key to fold in
   * @param canonicalFamilyKey the family key the alias collapses onto
   */
  private static void register(
      @NotNull Map<String, String> map,
      @NotNull String aliasFamilyKey,
      @NotNull String canonicalFamilyKey) {
    map.put(aliasFamilyKey, canonicalFamilyKey);
  }
}
