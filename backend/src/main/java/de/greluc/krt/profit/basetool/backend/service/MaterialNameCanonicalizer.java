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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * Folds commodity names so external spellings meet the local {@code material} catalogue (e.g.
 * {@code "Stileron (Raw)"} and {@code "STILERON (ORE)"} both become {@code "stileron"}).
 *
 * <p>Folding lowercases, drops parenthetical groups and the qualifiers {@code raw / ore / refined /
 * pure / r}, and strips non-alphanumerics inside each word.
 */
public final class MaterialNameCanonicalizer {

  /** Qualifier words dropped when computing a commodity's canonical core. */
  private static final Set<String> CANONICAL_QUALIFIER_WORDS =
      Set.of("raw", "ore", "refined", "pure", "r");

  /** Matches a trailing or inline parenthetical group, e.g. {@code " (Ore)"}. */
  private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)");

  /** Matches any run of non-alphanumeric characters, used to fold names to a canonical core. */
  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

  private MaterialNameCanonicalizer() {}

  /**
   * Computes a commodity's canonical core with all word boundaries removed, for exact matching:
   * {@code "Raw Silicon"}, {@code "Silicon (Raw)"} and {@code "SILICON"} all yield {@code
   * "silicon"}.
   *
   * @param name the raw commodity name
   * @return the canonical core, or {@code null} for null / blank input
   */
  @Contract("null -> null")
  public static @Nullable String canonicalCore(@Nullable String name) {
    if (!StringUtils.hasText(name)) {
      return null;
    }
    StringBuilder core = new StringBuilder();
    for (String folded : foldedWords(name)) {
      core.append(folded);
    }
    return core.toString();
  }

  /**
   * Computes the same folding as {@link #canonicalCore(String)} but keeps single spaces between
   * words, for token-based fuzzy matching.
   *
   * @param name the raw commodity name
   * @return the space-joined canonical key, or {@code null} for null / blank input
   */
  @Contract("null -> null")
  public static @Nullable String fuzzyKey(@Nullable String name) {
    if (!StringUtils.hasText(name)) {
      return null;
    }
    StringJoiner joiner = new StringJoiner(" ");
    for (String folded : foldedWords(name)) {
      joiner.add(folded);
    }
    return joiner.toString();
  }

  /**
   * Applies the shared folding pipeline and yields the surviving lowercase alphanumeric words:
   * parentheticals removed, qualifier words dropped, non-alphanumerics stripped per word, empties
   * skipped.
   *
   * @param name a non-blank commodity name
   * @return the folded words in original order (possibly empty)
   */
  @NotNull
  private static Iterable<String> foldedWords(@NotNull String name) {
    String stripped = PARENTHETICAL.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(" ");
    List<String> words = new ArrayList<>();
    for (String word : stripped.split("\\s+")) {
      String folded = NON_ALNUM.matcher(word).replaceAll("");
      if (folded.isEmpty() || CANONICAL_QUALIFIER_WORDS.contains(folded)) {
        continue;
      }
      words.add(folded);
    }
    return words;
  }
}
