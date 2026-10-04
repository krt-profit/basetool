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

import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * The client ids of the exchange registry, the bounded vocabulary external clients are attributed
 * by (REQ-XCH-010); cached for {@link #TTL} and dropped by {@link #invalidate()} after a registry
 * change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnownExchangeClients {

  /** How long a loaded set of client ids is used before it is read again. */
  static final Duration TTL = Duration.ofSeconds(30);

  /** The registry the ids are read from. */
  private final ExchangeClientRepository clientRepository;

  /** The last loaded ids. */
  private volatile Set<String> clientIds = Set.of();

  /** When {@link #clientIds} was loaded, in {@link System#nanoTime()} terms. */
  private volatile long loadedAt;

  /** Whether {@link #clientIds} holds a load that {@link #invalidate()} has not dropped. */
  private volatile boolean loaded;

  /**
   * Tells whether a client id is in the registry, whatever the client's status.
   *
   * @param clientId the client id to look up
   * @return {@code true} when the registry holds it
   */
  public boolean isRegistered(@Nullable String clientId) {
    return clientId != null && !clientId.isBlank() && current().contains(clientId);
  }

  /** Drops the cached ids, so the next lookup reads the registry. */
  public void invalidate() {
    loaded = false;
  }

  /**
   * Returns the cached ids, reloading them when they are older than {@link #TTL} or dropped; a
   * failed reload keeps the previous ids.
   *
   * @return the registered client ids
   */
  private Set<String> current() {
    if (!loaded || System.nanoTime() - loadedAt > TTL.toNanos()) {
      try {
        clientIds = Set.copyOf(clientRepository.findAllClientIds());
        loadedAt = System.nanoTime();
        loaded = true;
      } catch (DataAccessException e) {
        log.warn("Could not read the exchange registry's client ids: {}", e.toString());
      }
    }
    return clientIds;
  }
}
