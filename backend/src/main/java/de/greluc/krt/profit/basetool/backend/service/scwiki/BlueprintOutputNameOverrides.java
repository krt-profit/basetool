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
 * Curated, developer-maintained corrections for blueprint {@code output_name}s that CIG mislabels
 * at the source (#327). A handful of crafting blueprints carry a wrong English localization string
 * in the game's {@code Data.p4k}; the SC Wiki mirrors it faithfully and {@code
 * ScWikiBlueprintSyncService} copies it verbatim, so the correct in-game name ends up under no
 * entry and the personal-blueprint import (which matches the in-game/log name against the master
 * built from {@code output_name}) can only fuzzy-suggest a wrong piece. No component between CIG
 * and the basetool introduces the error, so the fix is this curated correction layer applied inside
 * the sync — keyed on the structurally-correct {@code scwiki_key}.
 *
 * <p><b>Guarded &amp; self-healing.</b> A correction fires only when the incoming {@code
 * output_name}, normalized through {@link BlueprintNameNormalizer}, equals the equally-normalized
 * {@code expectedWrongName} registered for that key. The instant CIG (or the Wiki) changes or fixes
 * the string the guard stops matching and the upstream value passes through unchanged — the
 * override never overwrites a name it does not recognise, so it cannot go stale silently. The
 * sync's loop detects the "key still present but the wrong name is gone" case via {@link
 * #isRegistered} + {@link #fires} and reports it (see {@code
 * SyncEventType#BLUEPRINT_NAME_OVERRIDE_OBSOLETE}) so the obsolete entry can be removed here.
 *
 * <p>This is a deliberately small, immutable code map — a DB-backed admin-managed table is an
 * explicit non-goal for this developer-curated set of known CIG bugs. The helper is stateless after
 * construction (it only reads the seeded map and the injected normalizer); all per-run bookkeeping
 * lives in the calling sync loop. Mirrors {@link BlueprintNameNormalizer}'s style as a pure,
 * constructor-injected {@link Component}.
 */
@Component
public class BlueprintOutputNameOverrides {

  /**
   * One curated correction: when the blueprint identified by {@code scwikiKey} arrives with an
   * {@code output_name} that normalizes to {@code expectedWrongName}, the sync stores {@code
   * correctedName} instead. {@code expectedWrongName} is the known-wrong upstream string (the guard
   * target); {@code correctedName} is the in-game-correct name the import matches against.
   *
   * @param scwikiKey the structurally-correct SC Wiki key the correction is keyed on (e.g. {@code
   *     BP_CRAFT_qrt_specialist_heavy_arms_01_01_13})
   * @param expectedWrongName the CIG-mislabeled upstream name that must match (pre-normalization)
   *     for the correction to fire
   * @param correctedName the in-game-correct replacement name written to {@code output_name}
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
   * Seeds the curated correction map (the two confirmed CIG-mislabeled QRT specialist-armor
   * blueprints, verified against the P4K export, the live SC Wiki API and the live UEX API) and
   * precomputes the normalized guard targets.
   *
   * @param normalizer the shared blueprint-name normalizer used to compare incoming names against
   *     the registered wrong names (and the same normalizer the import/search use)
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
   * Whether a curated correction is registered for this {@code scwiki_key} at all (regardless of
   * whether it would fire). Used by the sync loop to record that a correction-bearing key was
   * "seen" in the feed this run, which — combined with {@link #fires} returning {@code false} —
   * flags the correction as obsolete.
   *
   * @param scwikiKey the inbound blueprint key (whitespace-trimmed before lookup); may be {@code
   *     null}
   * @return {@code true} if a correction exists for the key
   */
  @Contract("null -> false")
  public boolean isRegistered(@Nullable String scwikiKey) {
    String key = canonicalKey(scwikiKey);
    return key != null && byKey.containsKey(key);
  }

  /**
   * Whether the correction for this key would fire for the given incoming name — i.e. a correction
   * is registered and the normalized incoming {@code output_name} equals the normalized {@code
   * expectedWrongName}. This is the self-healing guard: a non-matching (changed / fixed) upstream
   * name yields {@code false}.
   *
   * @param scwikiKey the inbound blueprint key (whitespace-trimmed before lookup); may be {@code
   *     null}
   * @param incomingOutputName the upstream {@code output_name} from the feed; may be {@code null}
   * @return {@code true} if the correction matches and would replace the name
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
   * Returns the curated in-game-correct name when the guard {@linkplain #fires fires} for this key
   * + incoming name, otherwise the incoming name unchanged (including {@code null}). This is the
   * call-site entry point applied immediately before {@code blueprint.setOutputName(...)}.
   *
   * @param scwikiKey the inbound blueprint key (whitespace-trimmed before lookup); may be {@code
   *     null}
   * @param incomingOutputName the upstream {@code output_name} from the feed; may be {@code null}
   * @return the corrected name if the correction fires, else {@code incomingOutputName} verbatim
   */
  @Contract("_, !null -> !null")
  public @Nullable String correct(@Nullable String scwikiKey, @Nullable String incomingOutputName) {
    if (fires(scwikiKey, incomingOutputName)) {
      return byKey.get(canonicalKey(scwikiKey)).correctedName();
    }
    return incomingOutputName;
  }

  /**
   * Looks up the registered correction for a key, for callers (the obsolescence reporter) that need
   * the {@code expectedWrongName} / {@code correctedName} to build a report detail.
   *
   * @param scwikiKey the inbound blueprint key (whitespace-trimmed before lookup); may be {@code
   *     null}
   * @return the correction, or empty when none is registered for the key
   */
  @NotNull
  public Optional<Correction> findByKey(@Nullable String scwikiKey) {
    String key = canonicalKey(scwikiKey);
    return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(key));
  }

  /**
   * Registers one correction under its own key (the key is stored both as the map key and on the
   * {@link Correction} record for self-description).
   *
   * @param map the map being seeded
   * @param scwikiKey the SC Wiki key to key the correction on
   * @param expectedWrongName the known-wrong upstream name (guard target)
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
   * Trims a candidate key to its canonical lookup form (SC Wiki keys are case-sensitive engine
   * identifiers, so only surrounding whitespace is folded — never case). Returns {@code null} for a
   * {@code null} input so callers can guard the immutable map (which rejects {@code null} keys).
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
