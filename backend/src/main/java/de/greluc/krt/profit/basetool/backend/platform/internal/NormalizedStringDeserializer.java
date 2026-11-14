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

package de.greluc.krt.profit.basetool.backend.platform.internal;

import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.StringUtils;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Jackson deserializer that trims, NFC-normalizes and length-caps every JSON string field.
 *
 * <p>An all-whitespace string becomes {@code null}. A string longer than its cap throws {@link
 * IllegalArgumentException}, mapped to 400. The cap is {@link
 * StringNormalization#MAX_FREE_TEXT_LENGTH}, or the property's larger {@link Size#max()} ({@link
 * StringNormalization#capFor(int)}).
 */
@RequiredArgsConstructor
public class NormalizedStringDeserializer extends ValueDeserializer<String> {

  /** The inclusive maximum length after normalization. */
  private final int maxLength;

  /** Creates the deserializer with the default free-text cap. */
  public NormalizedStringDeserializer() {
    this(StringNormalization.MAX_FREE_TEXT_LENGTH);
  }

  /**
   * Returns a deserializer capped at the {@code String} property's declared {@link Size#max()} when
   * it exceeds the default cap, else one with the default cap; a container's {@link Size} bounds
   * its element count and leaves its strings at the default.
   *
   * @param ctxt the deserialization context
   * @param property the property being deserialized, or {@code null} for a root value
   * @return the deserializer for that property
   */
  @Override
  public ValueDeserializer<?> createContextual(
      DeserializationContext ctxt, @Nullable BeanProperty property) {
    Size size =
        property == null || !property.getType().hasRawClass(String.class)
            ? null
            : property.getAnnotation(Size.class);
    int declaredMax = size == null || size.max() == Integer.MAX_VALUE ? 0 : size.max();
    int cap = StringNormalization.capFor(declaredMax);
    return cap == maxLength ? this : new NormalizedStringDeserializer(cap);
  }

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
    return StringNormalization.normalizeAndCap(trimmed, maxLength);
  }
}
