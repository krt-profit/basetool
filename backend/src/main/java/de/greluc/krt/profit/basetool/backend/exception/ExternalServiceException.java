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

package de.greluc.krt.profit.basetool.backend.exception;

/**
 * Thrown when an upstream service (Keycloak, UEX, …) returns an error or is unreachable.
 *
 * <p>Mapped to {@code 502 Bad Gateway} with code {@code EXTERNAL_SERVICE_ERROR}. Its {@link
 * #disclosurePolicy()} is {@link ErrorDisclosurePolicy#SUPPRESSED}: the cause is logged at ERROR
 * and the client receives only a generic localized detail.
 */
public final class ExternalServiceException extends AppException {

  /**
   * Creates an {@code ExternalServiceException} with a description of the upstream problem.
   *
   * @param message human-readable summary; will be replaced by a localized generic detail before
   *     reaching the client
   */
  public ExternalServiceException(String message) {
    super(AppExceptionKind.EXTERNAL_SERVICE_ERROR, message);
  }

  /**
   * Creates the exception wrapping the original upstream failure; the cause is logged server-side
   * and never relayed to the client.
   *
   * @param message summary for the server log
   * @param cause underlying upstream failure
   */
  public ExternalServiceException(String message, Throwable cause) {
    super(AppExceptionKind.EXTERNAL_SERVICE_ERROR, message, cause);
  }
}
