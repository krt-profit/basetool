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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A catalogue entry as the feed names it: for a blueprint product the produced item's GUID, its
 * key, and for every entry the Basetool key a client echoes as {@code bt}, and the display name
 * (REQ-XCH-012, REQ-INV-070).
 *
 * @param guid a blueprint product's produced-item GUID, or {@code null} for every other entry
 * @param bt the Basetool key: for a blueprint the same opaque key as the feed entry, deprecated in
 *     favour of {@code guid}; for a material or item its id
 * @param name the display name
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeItemRefDto(@Nullable UUID guid, @NotNull String bt, @NotNull String name) {

  /**
   * Creates an entry without a produced-item GUID.
   *
   * @param bt the Basetool key
   * @param name the display name
   */
  public ExchangeItemRefDto(@NotNull String bt, @NotNull String name) {
    this(null, bt, name);
  }
}
