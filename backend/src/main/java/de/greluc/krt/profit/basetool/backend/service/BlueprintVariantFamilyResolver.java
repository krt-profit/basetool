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

import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Derives the variant family key of a blueprint product name, under which a base item and its
 * cosmetic variants (the base name with a quoted nickname) count as one craftable family.
 *
 * <p>The key is the normalized name with the quoted nickname stripped, then canonicalized through
 * {@link BlueprintVariantAliasOverrides}. Magazines get an atomic key that never joins a weapon
 * family. Both sides of every match must use {@link #familyKey(String)}.
 */
@Component
@RequiredArgsConstructor
public class BlueprintVariantFamilyResolver {

  /**
   * Prefix marking an <em>atomic</em> (magazine) family key. Begins with a leading space, which a
   * trimmed family key never has, so an atomic key can only ever equal another atomic key built
   * from the identical magazine name — never a (whitespace-trimmed) weapon family key.
   */
  private static final String MAGAZINE_KEY_PREFIX = " mag:";

  /** A quoted cosmetic nickname: an ASCII double-quoted run (the normalizer folds curly quotes). */
  private static final Pattern QUOTED_NICKNAME = Pattern.compile("\"[^\"]*\"");

  /**
   * Curly/typographic double-quote glyphs the normalizer folds to ASCII. Mirrored here so the
   * case-preserving {@link #displayBaseName(String)} (which does not run the normalizer) still
   * strips a curly-quoted nickname.
   */
  private static final Pattern CURLY_DOUBLE_QUOTE = Pattern.compile("[“”„‟]");

  /** Runs of whitespace, collapsed to a single space after the nickname is removed. */
  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

  /**
   * A capacity parenthetical: a {@code (… N cap …)} group with a digit run followed by the whole
   * word {@code cap}. Tested against the lowercased normalized name.
   */
  private static final Pattern CAPACITY_MAGAZINE = Pattern.compile("\\(\\s*\\d+\\s*cap\\b[^)]*\\)");

  /**
   * A standalone ammo-container noun ({@code magazine}, {@code battery}, {@code ammo box}), matched
   * as a whole word.
   */
  private static final Pattern AMMO_NOUN = Pattern.compile("\\b(?:magazine|battery|ammo box)\\b");

  private final BlueprintNameNormalizer normalizer;
  private final BlueprintVariantAliasOverrides aliasOverrides;

  /**
   * Computes the variant family key of a product name: an atomic key for a magazine, otherwise the
   * quoted nickname removed, whitespace re-collapsed and the result canonicalized through {@link
   * BlueprintVariantAliasOverrides}.
   *
   * @param name a blueprint product name; may be {@code null}
   * @return the family key; never {@code null}, empty for a blank name
   */
  @NotNull
  public String familyKey(@Nullable String name) {
    String normalized = normalizer.normalize(name);
    if (normalized.isEmpty()) {
      return "";
    }
    if (isMagazineNormalized(normalized)) {
      return MAGAZINE_KEY_PREFIX + normalized;
    }
    String stripped = QUOTED_NICKNAME.matcher(normalized).replaceAll(" ");
    stripped = WHITESPACE_RUN.matcher(stripped).replaceAll(" ").trim();
    return aliasOverrides.canonical(stripped);
  }

  /**
   * Computes the coverage match key for a product name: the {@link #familyKey(String) family key}
   * when variants count, otherwise the exact {@link BlueprintNameNormalizer#normalize normalized}
   * name. Both sides of a match must use this method.
   *
   * @param name a blueprint product name; may be {@code null}
   * @param countWithVariants whether cosmetic variants fold into one family
   * @return the match key; never {@code null}, empty for a blank name
   */
  @NotNull
  public String matchKey(@Nullable String name, boolean countWithVariants) {
    return countWithVariants ? familyKey(name) : normalizer.normalize(name);
  }

  /**
   * Whether the product name denotes a magazine, battery or ammo box rather than a craftable
   * weapon. The name is normalized first.
   *
   * @param name a blueprint product name; may be {@code null}
   * @return {@code true} if the name is detected as an ammo container
   */
  public boolean isMagazine(@Nullable String name) {
    return isMagazineNormalized(normalizer.normalize(name));
  }

  /**
   * Returns a case-preserving family label: the name with its quoted nickname removed (curly quotes
   * folded first). A magazine name is returned trimmed.
   *
   * @param name a base or variant product name of the family; may be {@code null}
   * @return the base label; never {@code null}, empty for a blank name
   */
  @NotNull
  public String displayBaseName(@Nullable String name) {
    if (name == null) {
      return "";
    }
    String stripped = CURLY_DOUBLE_QUOTE.matcher(name).replaceAll("\"");
    stripped = QUOTED_NICKNAME.matcher(stripped).replaceAll(" ");
    return WHITESPACE_RUN.matcher(stripped).replaceAll(" ").trim();
  }

  /**
   * Detects a magazine on an already-{@link BlueprintNameNormalizer#normalize normalized} name: a
   * numeric capacity parenthetical or a standalone ammo-container noun.
   *
   * @param normalized the normalized product name
   * @return {@code true} if the normalized name is an ammo container
   */
  private static boolean isMagazineNormalized(@NotNull String normalized) {
    return CAPACITY_MAGAZINE.matcher(normalized).find() || AMMO_NOUN.matcher(normalized).find();
  }
}
