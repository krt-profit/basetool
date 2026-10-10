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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The registry's content as the gateway sees it: the global switch and every client by client id
 * (REQ-XCH-003). Two snapshots are equal exactly when they grant the same access.
 *
 * @param enabled whether the exchange serves any request
 * @param clients every registry client by its Keycloak client id, sorted
 */
public record ExchangeRegistrySnapshot(
    boolean enabled, @NotNull @Unmodifiable SortedMap<String, Client> clients) {

  /**
   * Copies the client map so the snapshot cannot change under its reader.
   *
   * @param enabled whether the exchange serves any request
   * @param clients every registry client by its Keycloak client id
   */
  public ExchangeRegistrySnapshot {
    clients = Collections.unmodifiableSortedMap(new TreeMap<>(clients));
  }

  /**
   * One client's entry.
   *
   * @param displayName the product name
   * @param status whether the client may use the exchange
   * @param capabilities the granted scopes, sorted
   * @param minClientVersion the oldest served release, or {@code null}
   * @param requestsPerMinute the per-minute limit override, or {@code null}
   * @param writesPerDay the daily write quota override, or {@code null}
   */
  public record Client(
      @NotNull String displayName,
      @NotNull ExchangeClientStatus status,
      @NotNull @Unmodifiable List<String> capabilities,
      @Nullable String minClientVersion,
      @Nullable Integer requestsPerMinute,
      @Nullable Integer writesPerDay) {

    /**
     * Sorts and copies the capabilities.
     *
     * @param displayName the product name
     * @param status whether the client may use the exchange
     * @param capabilities the granted scopes
     * @param minClientVersion the oldest served release, or {@code null}
     * @param requestsPerMinute the per-minute limit override, or {@code null}
     * @param writesPerDay the daily write quota override, or {@code null}
     */
    public Client {
      capabilities = capabilities.stream().sorted().distinct().toList();
    }
  }

  /**
   * Builds a snapshot from the registry rows.
   *
   * @param enabled the global switch
   * @param rows the registry clients with their capabilities loaded
   * @return the snapshot
   */
  @NotNull
  public static ExchangeRegistrySnapshot of(
      boolean enabled, @NotNull Collection<ExchangeClient> rows) {
    SortedMap<String, Client> clients = new TreeMap<>();
    for (ExchangeClient row : rows) {
      clients.put(
          row.getClientId(),
          new Client(
              row.getDisplayName(),
              row.getStatus(),
              row.getCapabilities().stream().map(ExchangeCapability::getScope).toList(),
              row.getMinClientVersion(),
              row.getRequestsPerMinute(),
              row.getWritesPerDay()));
    }
    return new ExchangeRegistrySnapshot(enabled, clients);
  }

  /**
   * Combines the state before a change with the state after it into the state that grants no more
   * than either: the switch is on only if it is on in both, a client absent from either is left
   * out, a client suspended in either is suspended and keeps only the capabilities both grant.
   * Every other field keeps its value from {@code before}.
   *
   * @param before the registry before the change
   * @param after the registry after the change
   * @return the most restrictive combination
   */
  @NotNull
  @Contract(pure = true)
  public static ExchangeRegistrySnapshot restrictiveMerge(
      @NotNull ExchangeRegistrySnapshot before, @NotNull ExchangeRegistrySnapshot after) {
    SortedMap<String, Client> merged = new TreeMap<>();
    for (Map.Entry<String, Client> entry : before.clients().entrySet()) {
      Client then = entry.getValue();
      Client now = after.clients().get(entry.getKey());
      if (now == null) {
        continue;
      }
      List<String> capabilities = new ArrayList<>(then.capabilities());
      capabilities.retainAll(now.capabilities());
      ExchangeClientStatus status =
          then.status() == ExchangeClientStatus.SUSPENDED
                  || now.status() == ExchangeClientStatus.SUSPENDED
              ? ExchangeClientStatus.SUSPENDED
              : ExchangeClientStatus.ACTIVE;
      merged.put(
          entry.getKey(),
          new Client(
              then.displayName(),
              status,
              capabilities,
              then.minClientVersion(),
              then.requestsPerMinute(),
              then.writesPerDay()));
    }
    return new ExchangeRegistrySnapshot(before.enabled() && after.enabled(), merged);
  }

  /**
   * Tells whether going from {@code before} to {@code after} takes any access away.
   *
   * @param before the registry before the change
   * @param after the registry after the change
   * @return {@code true} for a switch-off, a suspension, a removed client or a removed capability
   */
  @Contract(pure = true)
  public static boolean restricts(
      @NotNull ExchangeRegistrySnapshot before, @NotNull ExchangeRegistrySnapshot after) {
    return !restrictiveMerge(before, after).equals(before);
  }
}
