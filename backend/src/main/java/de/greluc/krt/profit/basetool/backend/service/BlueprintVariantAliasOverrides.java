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
 * Curated, developer-maintained equivalences between blueprint <em>variant family keys</em> that
 * the structural {@link BlueprintVariantFamilyResolver} cannot merge on its own. The resolver
 * merges a cosmetic variant into its base purely by stripping the quoted nickname (e.g. {@code
 * Fresnel "Molten" Energy LMG} → {@code fresnel energy lmg}); that conservative rule deliberately
 * leaves two residual classes of real same-line products in separate families:
 *
 * <ul>
 *   <li><b>Base-name spelling drift</b> — the SC Wiki spells one family's base inconsistently
 *       across its variants (e.g. {@code Pulse "Blacklist" Pistol} → {@code pulse pistol} but
 *       {@code Pulse "ArcCorp" Laser Pistol} → {@code pulse laser pistol}); the difference is in
 *       the <em>unquoted</em> residue, so quote-stripping cannot reconcile it.
 *   <li><b>Unquoted sub-models</b> — same weapon line, distinguished by an inline personal name or
 *       revision designation rather than a quoted skin (e.g. {@code Salvo Esteban Frag Pistol},
 *       {@code Model II Arclight}).
 * </ul>
 *
 * <p>This layer maps each known <em>alias</em> family key to the <em>canonical</em> family key the
 * family should collapse onto, applied as the final step of {@link
 * BlueprintVariantFamilyResolver#familyKey(String)} so both required-side and owned-side
 * derivations canonicalize identically. Like {@code BlueprintOutputNameOverrides} it is a small,
 * immutable code map — a DB-backed admin table is an explicit non-goal — and it is <b>guarded /
 * self-healing</b>: an entry rewrites only the exact alias key it recognises, so a non-matching
 * (renamed, corrected, or absent) upstream name simply passes through unchanged and the entry
 * quietly becomes a no-op rather than mismerging anything. Each entry is a deliberate game-domain
 * judgement (the two products genuinely share craftability) and is reviewed in code, not at
 * runtime.
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
   * Canonicalizes a structural family key: returns the registered canonical family key when {@code
   * familyKey} is a known alias, otherwise the key unchanged. Called as the last step of {@link
   * BlueprintVariantFamilyResolver#familyKey(String)}; a {@code null} or unregistered key passes
   * through verbatim so the conservative structural result wins whenever no curated entry applies.
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
   * Registers one alias → canonical mapping in family-key form.
   *
   * @param map the map being seeded
   * @param aliasFamilyKey the family key produced by the structural resolver for the
   *     variant/spelling that must be folded in
   * @param canonicalFamilyKey the family key the alias collapses onto (the canonical base)
   */
  private static void register(
      @NotNull Map<String, String> map,
      @NotNull String aliasFamilyKey,
      @NotNull String canonicalFamilyKey) {
    map.put(aliasFamilyKey, canonicalFamilyKey);
  }
}
