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
 * Derives the canonical {@code product_key} from a blueprint product name. The personal-blueprint
 * feature (#327) models ownership per product and matches names across two catalogues (SC Wiki and
 * the SCMDB log-watcher export) that spell the same product slightly differently, so both the
 * search and the import normalize names through this single helper before comparing them.
 *
 * <p>Normalization is intentionally conservative — it only folds the differences that actually
 * drift between the sources (surrounding whitespace, internal whitespace runs, the various Unicode
 * quote/apostrophe glyphs, and letter case). It does <strong>not</strong> strip punctuation
 * wholesale, which could collide genuinely distinct products.
 */
@Component
public class BlueprintNameNormalizer {

  /**
   * Normalizes a raw product name into its {@code product_key}: trims, collapses internal
   * whitespace to single spaces, folds Unicode double/single quote glyphs to their ASCII forms, and
   * lowercases using {@link Locale#ROOT}. A {@code null} or all-whitespace input yields an empty
   * string.
   *
   * @param raw the raw product name (SC Wiki output name or SCMDB product name); may be {@code
   *     null}
   * @return the normalized product key, never {@code null}
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
