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

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * Folds text for the quick access's server-side matching exactly as {@code krtSearchFold} does in
 * the browser: lower case, diacritics dropped, {@code ss} for the sharp s, and the spelled-out
 * umlauts {@code ae}, {@code oe}, {@code ue} collapsed like their umlaut letters (REQ-UI-026).
 */
public final class QuickAccessFold {

  private static final String DIGRAPH_VOWELS = "aou";
  private static final String SHARP_S = "ß";
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");

  private QuickAccessFold() {}

  /**
   * Folds a text.
   *
   * @param text the text to fold
   * @return the folded text
   */
  public static @NotNull String fold(@NotNull String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      String lower = String.valueOf(text.charAt(i)).toLowerCase(Locale.ROOT);
      String base =
          SHARP_S.equals(lower)
              ? "ss"
              : MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
      out.append(base);
      if (base.length() == 1
          && DIGRAPH_VOWELS.contains(base)
          && i + 1 < text.length()
          && Character.toLowerCase(text.charAt(i + 1)) == 'e') {
        i++;
      }
    }
    return out.toString();
  }

  /**
   * Whether a text contains an already folded term once folded itself.
   *
   * @param text the candidate text
   * @param foldedTerm the term, already folded
   * @return {@code true} when the folded text contains the term
   */
  public static boolean matches(@NotNull String text, @NotNull String foldedTerm) {
    return fold(text).contains(foldedTerm);
  }

  /**
   * Whether a text starts with an already folded term once folded itself.
   *
   * @param text the candidate text
   * @param foldedTerm the term, already folded
   * @return {@code true} when the folded text starts with the term
   */
  public static boolean startsWith(@NotNull String text, @NotNull String foldedTerm) {
    return fold(text).startsWith(foldedTerm);
  }
}
