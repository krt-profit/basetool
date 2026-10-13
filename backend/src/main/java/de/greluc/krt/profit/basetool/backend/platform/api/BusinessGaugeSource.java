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

package de.greluc.krt.profit.basetool.backend.platform.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.jetbrains.annotations.NotNull;

/**
 * A module's part of the business gauges: the queue depths and oldest-waiting ages it owns
 * (REQ-OBS-011).
 *
 * <p>The composition root registers every source once at startup and calls {@link #sample()} on the
 * monitoring interval inside one read-only transaction.
 */
public interface BusinessGaugeSource {

  /**
   * Registers the module's gauges against its holders, so each series exists, reporting {@code 0},
   * before the first {@link #sample()}.
   *
   * @param registry the registry to register the gauges with
   */
  void register(@NotNull MeterRegistry registry);

  /** Re-reads the module's queue depths and ages into the holders {@link #register} bound. */
  void sample();
}
