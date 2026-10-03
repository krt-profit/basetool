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

package de.greluc.krt.profit.basetool.ingest.auth;

import de.greluc.krt.profit.basetool.ingest.config.ExchangeLimitProperties;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.jetbrains.annotations.NotNull;

/**
 * The gateway's two DPoP {@code jti} replay caches, one for the exchange routes and one for every
 * other path, so a flood outside the exchange never refuses the exchange's proofs (REQ-XCH-006).
 *
 * @param exchange the cache of the {@code /exchange/} routes
 * @param other the cache of every other path
 */
public record DpopProofReplayStores(
    @NotNull DpopProofReplayStore exchange, @NotNull DpopProofReplayStore other) {

  /**
   * Creates both caches with the configured caps.
   *
   * @param limits the per-member and total caps
   * @param meterRegistry where the refusals are counted
   * @return the two caches
   */
  public static @NotNull DpopProofReplayStores of(
      @NotNull ExchangeLimitProperties limits, @NotNull MeterRegistry meterRegistry) {
    Clock clock = Clock.systemUTC();
    return new DpopProofReplayStores(
        new DpopProofReplayStore(
            MetricNames.PATH_SCOPE_EXCHANGE,
            limits.dpopProofsPerMember(),
            limits.dpopProofsTotal(),
            meterRegistry,
            clock),
        new DpopProofReplayStore(
            MetricNames.PATH_SCOPE_OTHER,
            limits.dpopProofsPerMember(),
            limits.dpopProofsTotal(),
            meterRegistry,
            clock));
  }
}
