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

import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Derives the canonical {@code product_key} from a blueprint product name, so names from the SC
 * Wiki and the SCMDB export compare equal.
 *
 * <p>Folds only whitespace, Unicode quote glyphs and case; punctuation is not stripped.
 */
@Component
public class BlueprintNameNormalizer {

  /**
   * Normalizes a raw product name into its {@code product_key}: trims, collapses internal
   * whitespace, folds Unicode quote glyphs to ASCII and lowercases with {@link Locale#ROOT}.
   *
   * @param raw the raw product name; may be {@code null}
   * @return the normalized key, empty for a {@code null} or blank input
   */
  @NotNull
  public String normalize(@Nullable String raw) {
    if (raw == null) {
      return "";
    }
    String s = raw.trim();
    if (s.isEmpty()) {
      return "";
    }
    s = s.replace('“', '"').replace('”', '"').replace('„', '"').replace('‟', '"');
    s =
        s.replace('‘', '\'')
            .replace('’', '\'')
            .replace('‚', '\'')
            .replace('‛', '\'')
            .replace('′', '\'')
            .replace('`', '\'');
    s = s.replaceAll("\\s+", " ");
    return s.toLowerCase(Locale.ROOT);
  }
}
