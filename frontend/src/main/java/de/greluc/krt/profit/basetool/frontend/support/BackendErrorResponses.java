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

package de.greluc.krt.profit.basetool.frontend.support;

import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Shared relay of a backend {@link BackendServiceException} back to the browser as {@code
 * application/problem+json}, replacing the byte-for-byte-identical {@code propagateBackendError}
 * method that the in-place-AJAX page controllers each carried privately.
 *
 * <p>The emitted body preserves the backend's stable {@code code} (e.g. {@code OPTIMISTIC_LOCK})
 * and status plus, when present, the problem {@code detail} and the {@code correlationId}, so the
 * shared client-side {@code krtFetch} branches on the conflict semantics exactly as it did before.
 *
 * <p>{@link #relay(Logger, String, BackendCall)} wraps the whole {@code try}/{@code catch} that
 * surrounds an AJAX handler's backend call, collapsing the boilerplate the {@code @ResponseBody}
 * write handlers each repeated: run the call, relay a {@link BackendServiceException} as {@code
 * problem+json} (preserving the {@code krtFetch} conflict semantics), and turn any other failure
 * into an empty {@code 500}.
 */
public final class BackendErrorResponses {

  private BackendErrorResponses() {}

  /**
   * The backend-touching body of an AJAX write handler: it performs the {@code backendApiClient}
   * call and builds the success {@link ResponseEntity}. It may fail with a {@link
   * BackendServiceException} (relayed as {@code problem+json}) or any other runtime exception
   * (turned into a {@code 500}); no checked exception is declared because the WebClient wrapper
   * throws only unchecked failures.
   */
  @FunctionalInterface
  public interface BackendCall {

    /**
     * Runs the backend call and returns the success response.
     *
     * @return the {@code 2xx} success response the handler would have returned inline
     */
    ResponseEntity<Object> call();
  }

  /**
   * Runs an AJAX handler's backend call and uniformly maps its failures, replacing the {@code try {
   * &hellip; } catch (BackendServiceException) { log; propagateBackendError } catch (Exception) {
   * log; 500 }} block the {@code @ResponseBody} write handlers each hand-rolled.
   *
   * <p>On success the {@code action}'s own response is returned unchanged. A {@link
   * BackendServiceException} is logged at {@code DEBUG} (status + message, matching the per-handler
   * lines it replaces — no PII beyond what those already logged) and relayed via {@link
   * #propagateBackendError} so the client keeps the exact conflict/toast semantics. Any other
   * exception is logged at {@code ERROR} (with the stack trace) and answered with an empty {@code
   * 500}. The caller passes its own {@link Logger} so the line keeps the controller's logger name
   * (per-class level config still applies) and an {@code operation} label so the "which call
   * failed" signal survives centralisation.
   *
   * <p>Handlers whose {@code BackendServiceException} branch does something other than {@link
   * #propagateBackendError} (a bare status passthrough, a {@code BackendErrorLogging.warn}
   * structured line, an extra {@code WebClientResponseException} catch) keep their bespoke block —
   * this helper only carries the plain propagate-or-500 shape.
   *
   * @param log the calling controller's logger, so the failure line keeps that logger name
   * @param operation a short human-readable label for the attempted operation (e.g. {@code "update
   *     status for order " + id}) woven into the log line
   * @param action the backend call plus success-response build to run
   * @return the {@code action}'s success response, a relayed {@code problem+json}, or an empty
   *     {@code 500}
   */
  @NotNull
  public static ResponseEntity<Object> relay(
      @NotNull Logger log, @NotNull String operation, @NotNull BackendCall action) {
    try {
      return action.call();
    } catch (BackendServiceException e) {
      log.debug("{} failed (status {}): {}", operation, e.getStatusCode(), e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("{} failed unexpectedly", operation, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Builds a {@code problem+json} {@link ResponseEntity} mirroring the backend failure: the backend
   * HTTP status, the stable problem {@code code}, and the optional {@code detail}/{@code
   * correlationId} (each omitted when blank/absent).
   *
   * @param e the backend failure to relay
   * @return a {@link ResponseEntity} carrying the backend status and an {@code
   *     application/problem+json} body
   */
  @NotNull
  public static ResponseEntity<Object> propagateBackendError(@NotNull BackendServiceException e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", e.getStatusCode());
    body.put("code", e.getProblemCode());
    if (e.getProblemDetail() != null && !e.getProblemDetail().isBlank()) {
      body.put("detail", e.getProblemDetail());
    }
    if (e.getCorrelationId() != null && !e.getCorrelationId().isBlank()) {
      body.put("correlationId", e.getCorrelationId());
    }
    return ResponseEntity.status(e.getStatusCode())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
