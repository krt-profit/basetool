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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import jakarta.validation.constraints.Size;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * The org unit a ship upsert names (REQ-XCH-017): read from a JSON string, or from an explicit JSON
 * {@code null} as the choice of no unit. A field the op leaves out reads as no choice at all, a
 * Java {@code null}.
 *
 * @param orgUnitId the unit's id as the client sent it, or {@code null} for none
 */
@JsonDeserialize(using = ExchangeOrgUnitChoice.Reader.class)
public record ExchangeOrgUnitChoice(@Nullable @Size(min = 1, max = 128) String orgUnitId) {

  /** The choice of no unit. */
  private static final ExchangeOrgUnitChoice NONE = new ExchangeOrgUnitChoice(null);

  /** Reads a unit id, an explicit {@code null} and an absent field apart. */
  static final class Reader extends StdDeserializer<ExchangeOrgUnitChoice> {

    /** Creates the reader. */
    Reader() {
      super(ExchangeOrgUnitChoice.class);
    }

    @Override
    public @NotNull ExchangeOrgUnitChoice deserialize(
        @NotNull JsonParser parser, @NotNull DeserializationContext context) {
      if (!parser.hasToken(JsonToken.VALUE_STRING)) {
        return (ExchangeOrgUnitChoice)
            context.handleUnexpectedToken(ExchangeOrgUnitChoice.class, parser);
      }
      return new ExchangeOrgUnitChoice(parser.getString());
    }

    @Override
    public @NotNull ExchangeOrgUnitChoice getNullValue(DeserializationContext context) {
      return NONE;
    }

    @Override
    public @Nullable Object getAbsentValue(DeserializationContext context) {
      return null;
    }
  }
}
