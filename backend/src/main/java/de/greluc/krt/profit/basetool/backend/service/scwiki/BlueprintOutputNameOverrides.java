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

package de.greluc.krt.profit.basetool.backend.service.scwiki;

import de.greluc.krt.profit.basetool.backend.service.BlueprintNameNormalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Curated corrections for blueprint {@code output_name}s that CIG mislabels at the source, applied
 * by {@code ScWikiBlueprintSyncService} and keyed on {@code scwiki_key}.
 *
 * <p>A correction fires only when the incoming name, normalized with {@link
 * BlueprintNameNormalizer}, equals the registered wrong name; otherwise the upstream value passes
 * through. {@link #isRegistered} and {@link #fires} let the sync report obsolete entries.
 */
@Component
public class BlueprintOutputNameOverrides {

  /**
   * One curated correction: when {@code scwikiKey} arrives with a name normalizing to {@code
   * expectedWrongName}, {@code correctedName} is stored instead.
   *
   * @param scwikiKey the SC Wiki key the correction is keyed on
   * @param expectedWrongName the mislabeled upstream name that must match for the correction to
   *     fire
   * @param correctedName the in-game-correct replacement name
   */
  public record Correction(
      @NotNull String scwikiKey,
      @NotNull String expectedWrongName,
      @NotNull String correctedName) {}

  private final BlueprintNameNormalizer normalizer;

  /**
   * Curated corrections keyed by their (trimmed) {@code scwiki_key}. Immutable after construction.
   */
  private final Map<String, Correction> byKey;

  /**
   * Precomputed normalized {@code expectedWrongName} per {@code scwiki_key}, so {@link #fires} is a
   * single normalize-and-compare against a stable value. Immutable after construction.
   */
  private final Map<String, String> normalizedWrongByKey;

  /**
   * Seeds the curated correction map and precomputes the normalized guard targets.
   *
   * @param normalizer the blueprint-name normalizer used to compare incoming names
   */
  public BlueprintOutputNameOverrides(@NotNull BlueprintNameNormalizer normalizer) {
    this.normalizer = normalizer;
    Map<String, Correction> corrections = new LinkedHashMap<>();
    register(
        corrections,
        "BP_CRAFT_qrt_specialist_heavy_arms_01_01_13",
        "Antium Helmet Jet",
        "Antium Arms Maroon");
    register(
        corrections,
        "BP_CRAFT_qrt_specialist_heavy_helmet_01_01_12",
        "Antium Core Jet",
        "Antium Helmet Jet");
    this.byKey = Map.copyOf(corrections);
    Map<String, String> normalizedWrong = new LinkedHashMap<>();
    corrections.forEach(
        (key, c) -> normalizedWrong.put(key, normalizer.normalize(c.expectedWrongName())));
    this.normalizedWrongByKey = Map.copyOf(normalizedWrong);
  }

  /**
   * Checks whether a correction is registered for this key, regardless of whether it would fire.
   *
   * @param scwikiKey the inbound blueprint key, trimmed before lookup; may be {@code null}
   * @return {@code true} if a correction exists for the key
   */
  @Contract("null -> false")
  public boolean isRegistered(@Nullable String scwikiKey) {
    String key = canonicalKey(scwikiKey);
    return key != null && byKey.containsKey(key);
  }

  /**
   * Checks whether the correction for this key would fire, i.e. the normalized incoming name equals
   * the normalized {@code expectedWrongName}.
   *
   * @param scwikiKey the inbound blueprint key, trimmed before lookup; may be {@code null}
   * @param incomingOutputName the upstream {@code output_name}; may be {@code null}
   * @return {@code true} if the correction matches
   */
  @Contract("null, _ -> false")
  public boolean fires(@Nullable String scwikiKey, @Nullable String incomingOutputName) {
    String key = canonicalKey(scwikiKey);
    if (key == null) {
      return false;
    }
    String normalizedWrong = normalizedWrongByKey.get(key);
    return normalizedWrong != null
        && normalizedWrong.equals(normalizer.normalize(incomingOutputName));
  }

  /**
   * Returns the corrected name when the correction {@linkplain #fires fires}, otherwise the
   * incoming name unchanged.
   *
   * @param scwikiKey the inbound blueprint key, trimmed before lookup; may be {@code null}
   * @param incomingOutputName the upstream {@code output_name}; may be {@code null}
   * @return the corrected name, else {@code incomingOutputName} verbatim
   */
  @Contract("_, !null -> !null")
  public @Nullable String correct(@Nullable String scwikiKey, @Nullable String incomingOutputName) {
    if (fires(scwikiKey, incomingOutputName)) {
      return byKey.get(canonicalKey(scwikiKey)).correctedName();
    }
    return incomingOutputName;
  }

  /**
   * Looks up the registered correction for a key.
   *
   * @param scwikiKey the inbound blueprint key, trimmed before lookup; may be {@code null}
   * @return the correction, or empty when none is registered
   */
  @NotNull
  public Optional<Correction> findByKey(@Nullable String scwikiKey) {
    String key = canonicalKey(scwikiKey);
    return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key));
  }

  /**
   * Registers one correction under its key.
   *
   * @param map the map being seeded
   * @param scwikiKey the SC Wiki key to key the correction on
   * @param expectedWrongName the known-wrong upstream name
   * @param correctedName the in-game-correct replacement
   */
  private static void register(
      @NotNull Map<String, Correction> map,
      @NotNull String scwikiKey,
      @NotNull String expectedWrongName,
      @NotNull String correctedName) {
    map.put(scwikiKey, new Correction(scwikiKey, expectedWrongName, correctedName));
  }

  /**
   * Trims a key to its lookup form; case is preserved.
   *
   * @param scwikiKey the raw inbound key, or {@code null}
   * @return the trimmed key, or {@code null}
   */
  @Nullable
  @Contract("null -> null")
  private static String canonicalKey(@Nullable String scwikiKey) {
    return scwikiKey == null ? null : scwikiKey.trim();
  }
}
