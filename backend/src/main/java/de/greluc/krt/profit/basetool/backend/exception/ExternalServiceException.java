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
 * Thrown when an upstream service the backend depends on (Keycloak, UEX, …) returns an error
 * response or is unreachable.
 *
 * <p>Mapped to HTTP {@code 502 Bad Gateway} by {@link
 * de.greluc.krt.profit.basetool.backend.exception.GlobalExceptionHandler}'s generic {@code
 * AppException} dispatch handler with the stable error code {@code EXTERNAL_SERVICE_ERROR}. {@link
 * #disclosurePolicy()} is {@link ErrorDisclosurePolicy#SUPPRESSED} — inherited from {@link
 * AppExceptionKind#EXTERNAL_SERVICE_ERROR}, the fixed identity passed to the superclass constructor
 * — so the original cause is kept on the exception and logged server-side at ERROR; the {@code
 * detail} body returned to the client is a generic localized message so we do not echo back
 * implementation details (status codes, response bodies) of the upstream service to the caller.
 *
 * <p>Prefer this over a plain {@link RuntimeException} so an upstream outage surfaces as a clearly
 * distinguishable 5xx category in the client and in the logs, rather than being indistinguishable
 * from an unexpected internal bug ({@code 500 INTERNAL_ERROR}).
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
   * Creates an {@code ExternalServiceException} that wraps the original failure (network exception,
   * {@code RestClientResponseException}, …). The cause is logged server-side; the client receives a
   * generic localized detail to avoid leaking upstream implementation details.
   *
   * @param message human-readable summary for the server log
   * @param cause underlying upstream failure
   */
  public ExternalServiceException(String message, Throwable cause) {
    super(AppExceptionKind.EXTERNAL_SERVICE_ERROR, message, cause);
  }
}
