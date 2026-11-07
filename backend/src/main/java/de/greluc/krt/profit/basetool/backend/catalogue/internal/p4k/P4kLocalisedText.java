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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jetbrains.annotations.Nullable;

/**
 * A localised text of the catalogue: the localisation key and the resolved English and German
 * texts, each {@code null} while unknown. Escaped line breaks in {@code en} and {@code de} are
 * decoded on construction (REQ-P4K-045).
 *
 * @param key the localisation key, e.g. {@code @item_NameBEHR_LaserCannon_S2}
 * @param en the English text
 * @param de the German text
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kLocalisedText(@Nullable String key, @Nullable String en, @Nullable String de) {

  /**
   * Decodes escaped line breaks in both texts.
   *
   * @param key the localisation key
   * @param en the English text
   * @param de the German text
   */
  public P4kLocalisedText {
    en = P4kText.decodeLineBreaks(en);
    de = P4kText.decodeLineBreaks(de);
  }

  /**
   * Returns the English text of a possibly absent localised text.
   *
   * @param text the localised text, or {@code null}
   * @return its English text, or {@code null} when the text or its English form is absent
   */
  @Nullable
  public static String enOf(@Nullable P4kLocalisedText text) {
    return text == null ? null : text.en();
  }

  /**
   * Returns the German text of a possibly absent localised text.
   *
   * @param text the localised text, or {@code null}
   * @return its German text, or {@code null} when the text or its German form is absent
   */
  @Nullable
  public static String deOf(@Nullable P4kLocalisedText text) {
    return text == null ? null : text.de();
  }

  /**
   * Returns the localisation key of a possibly absent localised text.
   *
   * @param text the localised text, or {@code null}
   * @return its key, or {@code null} when the text or its key is absent
   */
  @Nullable
  public static String keyOf(@Nullable P4kLocalisedText text) {
    return text == null ? null : text.key();
  }
}
