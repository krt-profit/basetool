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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The backend's refusal of a caller a session gate holds: a {@code 403} whose problem body carries
 * {@value #MARKER_PROPERTY}{@code : true} (REQ-SEC-028, REQ-PATCH-020, ADR-0385).
 *
 * <p>The marker, not the code, makes a refusal a gate's, so the kernel names no gate. Like a lost
 * session it is expected and self-clearing: logged at {@code DEBUG}, not counted as a failed
 * backend call, never swallowed by a page section's fallback; the error handler hands the browser to
 * the gate whose code it carries.
 */
public final class SessionGateRefusalException extends BackendServiceException {

  /** The problem property the backend sets on every session gate's refusal. */
  public static final String MARKER_PROPERTY = "sessionGate";

  /**
   * Creates the refusal parsed from a marked problem body.
   *
   * @param message the developer-facing message
   * @param cause the backend response the refusal was parsed from
   * @param statusCode the HTTP status, {@code 403}
   * @param problemCode the gate's {@code code}
   * @param correlationId the backend's correlation id
   * @param problemDetail the backend's localized detail
   */
  public SessionGateRefusalException(
      @NotNull String message,
      @Nullable Throwable cause,
      int statusCode,
      @NotNull String problemCode,
      @Nullable String correlationId,
      @Nullable String problemDetail) {
    super(message, cause, statusCode, problemCode, correlationId, List.of(), problemDetail);
  }
}
