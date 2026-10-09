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

/** The mirror used while Redis mirroring is off: it stores nothing and reads nothing. */
final class DisabledExchangeRegistryMirror implements ExchangeRegistryMirror {

  /**
   * Always {@code false}.
   *
   * @return {@code false}
   */
  @Override
  public boolean isActive() {
    return false;
  }

  /**
   * Discards the snapshot.
   *
   * @param snapshot ignored
   * @param revision ignored
   */
  @Override
  public void write(@NotNull ExchangeRegistrySnapshot snapshot, long revision) {}

  /**
   * Always empty.
   *
   * @return empty
   */
  @NotNull
  @Override
  public Optional<ExchangeRegistrySnapshot> read() {
    return Optional.empty();
  }
}
