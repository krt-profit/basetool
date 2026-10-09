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

import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** Where the registry is mirrored for the gateway (REQ-XCH-003). */
public interface ExchangeRegistryMirror {

  /**
   * Tells whether this mirror stores anything; a disabled mirror accepts and returns nothing.
   *
   * @return {@code true} when writes reach Redis
   */
  boolean isActive();

  /**
   * Replaces the mirror document.
   *
   * @param snapshot the registry content to publish
   * @param revision the revision the document carries
   * @throws RuntimeException when the write did not reach Redis
   */
  void write(@NotNull ExchangeRegistrySnapshot snapshot, long revision);

  /**
   * Reads the registry content currently mirrored.
   *
   * @return the mirrored content, or empty when there is no readable document
   * @throws RuntimeException when Redis could not be read
   */
  @NotNull
  Optional<ExchangeRegistrySnapshot> read();
}
