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

package de.greluc.krt.profit.basetool.backend.config;

import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.StringUtils;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Jackson deserializer that trims, NFC-normalizes and length-caps every JSON string field.
 *
 * <p>Trimming is unconditional; an all-whitespace string deserializes as {@code null} rather than
 * an empty string so {@code @NotBlank} fires consistently. NFC normalization collapses Unicode
 * combining sequences into precomposed code points so {@code "café"} (one code point) and {@code
 * "cafe + ́"} (two code points) compare equal in the database. The length cap ({@link
 * StringNormalization#MAX_FREE_TEXT_LENGTH}) matches the longest free-text column in the schema; an
 * oversized payload throws {@link IllegalArgumentException} which {@code GlobalExceptionHandler}
 * maps to a 400.
 */
public class NormalizedStringDeserializer extends ValueDeserializer<String> {

  @Nullable
  @Override
  public String deserialize(JsonParser p, DeserializationContext ctxt) {
    String text = p.getValueAsString();
    if (text == null) {
      return null;
    }
    String trimmed = text.trim();
    if (!StringUtils.hasText(trimmed)) {
      return null;
    }
    return StringNormalization.normalizeAndCap(trimmed, StringNormalization.MAX_FREE_TEXT_LENGTH);
  }
}
