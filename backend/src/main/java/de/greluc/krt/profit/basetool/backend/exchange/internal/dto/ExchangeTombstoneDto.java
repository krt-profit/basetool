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
import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A removal in the change feed, naming who removed the entry (REQ-XCH-013).
 *
 * @param key the removed entry's opaque key
 * @param removedAt when it was removed
 * @param removedBy who removed it
 * @param clientKey a removed refinery order's key, only for the client that created it
 *     (REQ-XCH-051), or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeTombstoneDto(
    @NotNull String key,
    @NotNull Instant removedAt,
    @NotNull ExchangeRemovedByDto removedBy,
    @Nullable String clientKey) {

  /**
   * Creates a tombstone without a client key.
   *
   * @param key the removed entry's opaque key
   * @param removedAt when it was removed
   * @param removedBy who removed it
   */
  public ExchangeTombstoneDto(
      @NotNull String key, @NotNull Instant removedAt, @NotNull ExchangeRemovedByDto removedBy) {
    this(key, removedAt, removedBy, null);
  }

  /**
   * Returns this tombstone carrying a refinery order's client key.
   *
   * @param key the creating client's key, or {@code null}
   * @return the tombstone
   */
  public @NotNull ExchangeTombstoneDto withClientKey(@Nullable String key) {
    return new ExchangeTombstoneDto(this.key, removedAt, removedBy, key);
  }
}
