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

package de.greluc.krt.profit.basetool.frontend.purchasing.web;

import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingStateDto;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Whether the purchasing surface is switched on (REQ-PUR-001), read from the backend and remembered
 * for a short while, because the sidebar asks on every page. A failed read answers {@code false}
 * and is not remembered, so a member the backend refuses never hides the entry from everyone else.
 * A session gate's refusal is such a failure, because the sidebar also renders on the pages a gate
 * leaves open; only a lost session propagates (ADR-0385).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PurchasingFeatureState {

  /** How long a successful answer is kept. */
  static final Duration TTL = Duration.ofSeconds(30);

  private final PurchasingRequestState requestState;

  private volatile Remembered remembered;

  /**
   * Tells whether the purchasing entries are shown.
   *
   * @return {@code true} while the backend says the surface is on
   */
  public boolean enabled() {
    Remembered current = remembered;
    if (current != null && current.until().isAfter(Instant.now())) {
      return current.enabled();
    }
    PurchasingStateDto state;
    try {
      state = requestState.read();
    } catch (RuntimeException e) {
      SectionFallback.rethrowIfReauthenticationRequired(e);
      log.debug("Purchasing state unavailable: {}", e.getMessage());
      return false;
    }
    if (state == null) {
      return false;
    }
    remembered = new Remembered(state.enabled(), Instant.now().plus(TTL));
    return state.enabled();
  }

  /** Forgets the remembered answer, as an admin has just changed the switch. */
  public void evict() {
    remembered = null;
    requestState.forget();
  }

  private record Remembered(boolean enabled, Instant until) {}
}
