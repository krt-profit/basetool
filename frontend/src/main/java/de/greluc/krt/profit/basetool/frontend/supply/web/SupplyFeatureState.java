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

package de.greluc.krt.profit.basetool.frontend.supply.web;

import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStateDto;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Whether the supply surface is switched on (REQ-SUP-001), read from the backend and remembered for
 * a short while, because the sidebar asks on every page. A failed read answers {@code false} and is
 * not remembered, so a member the backend refuses never hides the entries from everyone else.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupplyFeatureState {

  /** How long a successful answer is kept. */
  static final Duration TTL = Duration.ofSeconds(30);

  private final SupplyBackendClient supplyClient;

  private volatile Remembered remembered;

  /**
   * Tells whether the supply entries are shown.
   *
   * @return {@code true} while the backend says the surface is on
   */
  public boolean enabled() {
    Remembered current = remembered;
    if (current != null && current.until().isAfter(Instant.now())) {
      return current.enabled();
    }
    try {
      SupplyStateDto state = supplyClient.state();
      boolean enabled = state != null && state.enabled();
      remembered = new Remembered(enabled, Instant.now().plus(TTL));
      return enabled;
    } catch (RuntimeException e) {
      log.debug("Supply state unavailable: {}", e.getMessage());
      return false;
    }
  }

  /** Forgets the remembered answer, as an admin has just changed the switch. */
  public void evict() {
    remembered = null;
  }

  private record Remembered(boolean enabled, Instant until) {}
}
