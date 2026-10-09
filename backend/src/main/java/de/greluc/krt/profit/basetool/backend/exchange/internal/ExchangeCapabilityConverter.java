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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.jetbrains.annotations.Nullable;

/** Stores an {@link ExchangeCapability} as its OAuth scope string. */
@Converter
public class ExchangeCapabilityConverter implements AttributeConverter<ExchangeCapability, String> {

  /**
   * Writes the capability's scope.
   *
   * @param attribute the capability, or {@code null}
   * @return the scope string, or {@code null}
   */
  @Nullable
  @Override
  public String convertToDatabaseColumn(@Nullable ExchangeCapability attribute) {
    return attribute == null ? null : attribute.getScope();
  }

  /**
   * Reads a capability back from its scope.
   *
   * @param dbData the stored scope, or {@code null}
   * @return the capability, or {@code null}
   * @throws IllegalArgumentException when the stored scope names no capability
   */
  @Nullable
  @Override
  public ExchangeCapability convertToEntityAttribute(@Nullable String dbData) {
    return dbData == null ? null : ExchangeCapability.ofScope(dbData);
  }
}
