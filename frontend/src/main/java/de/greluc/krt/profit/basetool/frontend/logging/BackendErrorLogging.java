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

package de.greluc.krt.profit.basetool.frontend.logging;

import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Structured WARN logging for {@link BackendServiceException}s caught in page controllers.
 *
 * <p>One line carries the action, HTTP status, {@code problemCode}, {@code correlationId}, {@code
 * detail} and {@code fieldErrors[]}; rejected user values are never logged.
 */
public final class BackendErrorLogging {

  private BackendErrorLogging() {}

  /**
   * Logs the given {@link BackendServiceException} at WARN level in a stable, parseable format.
   *
   * @param logger controller-local SLF4J logger
   * @param action short action identifier (e.g. {@code "createHandover"} or {@code "POST
   *     /api/v1/orders/{id}/handovers"}) - never user input
   * @param contextId optional resource id (e.g. JobOrder UUID) for triage; pass {@code null} when
   *     not applicable
   * @param ex the exception raised by the backend WebClient layer
   */
  public static void warn(
      @NotNull Logger logger,
      @NotNull String action,
      @Nullable Object contextId,
      @NotNull BackendServiceException ex) {
    if (!logger.isWarnEnabled()) {
      return;
    }
    if (contextId != null) {
      logger.warn(
          "Backend call failed [action={}, contextId={}]: status={}, code={}, correlationId={},"
              + " detail={}, fieldErrors={}",
          action,
          contextId,
          ex.getStatusCode(),
          ex.getProblemCode(),
          ex.getCorrelationId(),
          ex.getProblemDetail(),
          ex.getFieldErrors());
    } else {
      logger.warn(
          "Backend call failed [action={}]: status={}, code={}, correlationId={}, detail={},"
              + " fieldErrors={}",
          action,
          ex.getStatusCode(),
          ex.getProblemCode(),
          ex.getCorrelationId(),
          ex.getProblemDetail(),
          ex.getFieldErrors());
    }
  }

  /** Convenience overload without a {@code contextId}. */
  public static void warn(
      @NotNull Logger logger, @NotNull String action, @NotNull BackendServiceException ex) {
    warn(logger, action, null, ex);
  }
}
