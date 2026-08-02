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

package de.greluc.krt.profit.basetool.ingest.support;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;

/**
 * Builds {@link LoggingProperties} instances for unit tests that construct a filter directly
 * instead of pulling it from a Spring context. Keeps the record's default values in one place so a
 * new component does not have to restate them.
 */
public final class TestLoggingProperties {

  private TestLoggingProperties() {
    // Test-support holder — not instantiable.
  }

  /**
   * Returns the production defaults: {@code X-Correlation-Id}, the {@code correlationId} / {@code
   * userId} MDC keys, a 2000 ms slow-request threshold and a 1500 ms slow-relay threshold.
   *
   * @return the default logging properties
   */
  public static LoggingProperties defaults() {
    return new LoggingProperties(
        "X-Correlation-Id", "correlationId", "userId", 2000L, 1500L, false);
  }

  /**
   * Returns the production defaults with the slow thresholds overridden, so a test can force the
   * WARN / {@code Slow backend call} branch without actually being slow.
   *
   * @param slowRequestThresholdMs the inbound slow-request threshold in milliseconds
   * @param slowBackendCallThresholdMs the outbound slow-relay threshold in milliseconds
   * @return logging properties carrying the given thresholds
   */
  public static LoggingProperties withThresholds(
      long slowRequestThresholdMs, long slowBackendCallThresholdMs) {
    return new LoggingProperties(
        "X-Correlation-Id",
        "correlationId",
        "userId",
        slowRequestThresholdMs,
        slowBackendCallThresholdMs,
        false);
  }
}
