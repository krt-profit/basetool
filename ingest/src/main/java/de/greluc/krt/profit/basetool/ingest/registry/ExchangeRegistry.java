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

package de.greluc.krt.profit.basetool.ingest.registry;

import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The exchange registry as the gateway reads it from the backend's Redis mirror (REQ-XCH-003).
 *
 * @param revision the mirror document's revision
 * @param enabled whether the exchange serves any request
 * @param clients every registry client by its Keycloak client id
 */
public record ExchangeRegistry(
    long revision, boolean enabled, @NotNull @Unmodifiable Map<String, Client> clients) {

  /** Copies the clients. */
  public ExchangeRegistry {
    clients = Map.copyOf(clients);
  }

  /**
   * One registry client.
   *
   * @param displayName the product name
   * @param active whether the client may use the exchange
   * @param capabilities the granted scopes
   * @param minClientVersion the oldest served release as {@code major.minor.patch}, or {@code null}
   * @param requestsPerMinute the per-minute limit override, or {@code null}
   * @param writesPerDay the daily write quota override, or {@code null}
   */
  public record Client(
      @NotNull String displayName,
      boolean active,
      @NotNull @Unmodifiable Set<String> capabilities,
      @Nullable String minClientVersion,
      @Nullable Integer requestsPerMinute,
      @Nullable Integer writesPerDay) {

    /** Copies the capabilities. */
    public Client {
      capabilities = Set.copyOf(capabilities);
    }
  }
}
