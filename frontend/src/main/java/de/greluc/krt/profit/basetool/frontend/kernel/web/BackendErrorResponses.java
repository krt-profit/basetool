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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendErrorMapper;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Relays a backend {@link BackendServiceException} to the browser as {@code
 * application/problem+json}, preserving the backend's status, stable {@code code}, and when present
 * {@code detail} and {@code correlationId}, for {@code krtFetch}.
 */
public final class BackendErrorResponses {

  private BackendErrorResponses() {}

  /**
   * The backend call and success-response build of an AJAX write handler, run by {@link #relay}; it
   * throws only unchecked exceptions.
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
   * Runs an AJAX handler's backend call and maps failures uniformly: a {@link
   * BackendServiceException} is logged at DEBUG and relayed via {@link #propagateBackendError}, a
   * {@link ReauthenticationRequiredException} propagates to the global handler, any other exception
   * is logged at ERROR and answered with an empty {@code 500}.
   *
   * @param log the calling controller's logger
   * @param operation a short label of the attempted operation for the log line
   * @param action the backend call plus success-response build to run
   * @return the {@code action}'s response, a relayed {@code problem+json}, or an empty {@code 500}
   */
  @NotNull
  public static ResponseEntity<Object> relay(
      @NotNull Logger log, @NotNull String operation, @NotNull BackendCall action) {
    try {
      return action.call();
    } catch (BackendServiceException e) {
      log.debug("{} failed (status {}): {}", operation, e.getStatusCode(), e.getMessage());
      return propagateBackendError(e);
    } catch (ReauthenticationRequiredException e) {
      throw e;
    } catch (Exception e) {
      log.error("{} failed unexpectedly", operation, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Runs a backend call from a proxy whose callers read the HTTP status (a {@code fetch} that tests
   * {@code response.ok}, a download link): a {@link BackendServiceException} is rethrown as a
   * {@link ResponseStatusException} with the backend's status, so the global handler answers with
   * that status for JSON and HTML callers alike. A {@link ReauthenticationRequiredException} and
   * every other exception propagate unchanged.
   *
   * @param call the backend call, normally through {@code BackendApiClient#execute}
   * @param <T> the call's result type
   * @return the call's result
   * @throws ResponseStatusException carrying the backend status when the backend call failed
   */
  public static <T> T withBackendStatus(@NotNull Supplier<T> call) {
    try {
      return call.get();
    } catch (BackendServiceException e) {
      throw new ResponseStatusException(
          HttpStatusCode.valueOf(e.getStatusCode()), "The backend request failed.", e);
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

  /**
   * Relays a backend refusal a raw {@code WebClient} call raised, exactly as {@link
   * #propagateBackendError} relays one of {@code BackendApiClient}: status, {@code code}, and the
   * backend's localised {@code detail} when its body is a problem.
   *
   * @param e the backend's error response
   * @return an {@code application/problem+json} response with the backend's status
   */
  @NotNull
  public static ResponseEntity<Object> propagateBackendError(
      @NotNull WebClientResponseException e) {
    return propagateBackendError(BackendErrorMapper.parseProblem(e));
  }

  /**
   * Builds a {@code problem+json} refusal the frontend itself decided on, in the shape {@link
   * #propagateBackendError} relays a backend one, so a page reads both alike.
   *
   * @param status the refusal status
   * @param code the stable problem code
   * @param detail the localised detail the page shows
   * @return the refusal
   */
  @NotNull
  public static ResponseEntity<Object> problem(
      @NotNull HttpStatus status, @NotNull String code, @NotNull String detail) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", status.value());
    body.put("code", code);
    body.put("detail", detail);
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
  }
}
