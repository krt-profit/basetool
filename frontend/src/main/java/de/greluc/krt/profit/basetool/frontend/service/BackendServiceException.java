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

package de.greluc.krt.profit.basetool.frontend.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.ProblemDetail;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exception raised when a backend call performed by the frontend module fails.
 *
 * <p>Parses an RFC7807 Problem+JSON response body (as produced by the backend's {@code
 * GlobalExceptionHandler}) and exposes the stable {@code code}, {@code correlationId} and {@code
 * fieldErrors[]} so that the frontend controller advice can map them onto localized message keys
 * without leaking technical details or server stack traces to the user.
 *
 * <p>All reason codes originate from either the backend Problem+JSON response ({@code
 * properties.code}) or from resilience layer mappings (see {@link
 * BackendApiClient#handleWebClientException} / {@code handleException}).
 */
@Getter
public class BackendServiceException extends RuntimeException {

  /** Unknown / unmapped backend problem (fallback). */
  public static final String CODE_UNKNOWN = "UNKNOWN";

  /** Circuit breaker is open / backend unreachable / bulkhead saturated. */
  public static final String CODE_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

  /** The backend did not respond within the configured timeout. */
  public static final String CODE_BACKEND_TIMEOUT = "BACKEND_TIMEOUT";

  /**
   * The caller is authenticated but their registration is still pending admin approval. An
   * expected, self-inflicted 403 the pending user's shell polls on every page load — logged at
   * DEBUG rather than WARN so it does not flood the client-error log (mirrors the backend {@code
   * PendingApprovalAccessFilter}).
   */
  public static final String CODE_PENDING_APPROVAL = "PENDING_APPROVAL";

  private final int statusCode;
  private final @NotNull String problemCode;
  private final @Nullable String correlationId;
  private final @NotNull List<FieldError> fieldErrors;
  private final @Nullable String problemDetail;

  /**
   * Convenience constructor when only the HTTP status is known; problem code becomes {@code
   * CODE_UNKNOWN}.
   */
  public BackendServiceException(
      @NotNull String message, @Nullable Throwable cause, int statusCode) {
    this(message, cause, statusCode, CODE_UNKNOWN, null, Collections.emptyList(), null);
  }

  /**
   * Full constructor used by {@link #fromProblem(WebClientResponseException, ObjectMapper)} after
   * parsing RFC-7807.
   */
  public BackendServiceException(
      @NotNull String message,
      @Nullable Throwable cause,
      int statusCode,
      @NotNull String problemCode,
      @Nullable String correlationId,
      @NotNull List<FieldError> fieldErrors,
      @Nullable String problemDetail) {
    super(message, cause);
    this.statusCode = statusCode;
    this.problemCode = problemCode;
    this.correlationId = correlationId;
    this.fieldErrors = List.copyOf(fieldErrors);
    this.problemDetail = problemDetail;
  }

  /**
   * Attempts to extract a human-readable message from the underlying Problem+JSON body. Falls back
   * to the exception message if no Problem payload is available.
   */
  public @NotNull String getReadableErrorMessage() {
    if (problemDetail != null && !problemDetail.isBlank()) {
      return problemDetail;
    }
    if (getCause() instanceof WebClientResponseException wcre) {
      try {
        ProblemDetail pd = wcre.getResponseBodyAs(ProblemDetail.class);
        if (pd != null && pd.getDetail() != null) {
          return pd.getDetail();
        } else if (pd != null && pd.getTitle() != null) {
          return pd.getTitle();
        }
      } catch (Exception ignored) {
        // ProblemDetail body not present or unparseable — fall through to default message
      }
    }
    return String.valueOf(getMessage());
  }

  /**
   * Returns the trailing segment of the Problem {@code type} URI — kept for backwards compatibility
   * with existing call sites that previously used the {@code type} suffix as a discriminator.
   * Prefer {@link #getProblemCode()}.
   */
  public @Nullable String getProblemType() {
    if (getCause() instanceof WebClientResponseException wcre) {
      try {
        ProblemDetail pd = wcre.getResponseBodyAs(ProblemDetail.class);
        if (pd != null && pd.getType() != null) {
          String type = pd.getType().toString();
          if (type.contains("/")) {
            return type.substring(type.lastIndexOf("/") + 1);
          }
          return type;
        }
      } catch (Exception ignored) {
        // ProblemDetail body not present or unparseable — return null
      }
    }
    return null;
  }

  /**
   * Parses an RFC7807 Problem+JSON body produced by the backend and constructs a {@link
   * BackendServiceException}. When the body cannot be parsed, the exception still carries the HTTP
   * status and {@link #CODE_UNKNOWN} so the advice layer can fall back to a generic localized
   * message.
   */
  public static @NotNull BackendServiceException fromProblem(
      @NotNull WebClientResponseException cause, @NotNull ObjectMapper mapper) {
    int status = cause.getStatusCode().value();
    String body = cause.getResponseBodyAsString();
    String code = deriveCodeFromStatus(status);
    String correlationId = null;
    String detail = null;
    List<FieldError> fieldErrors = new ArrayList<>();

    if (body != null && !body.isBlank()) {
      try {
        JsonNode root = mapper.readTree(body);
        if (root.hasNonNull("code")) {
          code = root.get("code").asString(code);
        }
        if (root.hasNonNull("correlationId")) {
          correlationId = root.get("correlationId").asString();
        }
        if (root.hasNonNull("detail")) {
          detail = root.get("detail").asString();
        }
        JsonNode fieldErrorsNode = root.get("fieldErrors");
        if (fieldErrorsNode != null && fieldErrorsNode.isArray()) {
          for (JsonNode fe : fieldErrorsNode) {
            String field = fe.hasNonNull("field") ? fe.get("field").asString() : "";
            String msg = fe.hasNonNull("message") ? fe.get("message").asString() : "";
            fieldErrors.add(new FieldError(field, msg));
          }
        }
      } catch (Exception ignored) {
        // Leave defaults; the handler will fall back to a generic message.
      }
    }

    // deriveCodeFromStatus is @NotNull and Jackson's asText(default) never returns null,
    // so code is guaranteed non-null here. The previous "code != null" defensive guard
    // mis-signalled to SpotBugs that the author believed a null code was reachable, which
    // conflicted with the @NotNull problemCode parameter on the constructor below.
    String message = "Backend returned " + status + " [" + code + "]";
    return new BackendServiceException(
        message, cause, status, code, correlationId, fieldErrors, detail);
  }

  private static @NotNull String deriveCodeFromStatus(int status) {
    return switch (status) {
      case 400 -> "VALIDATION_FAILED";
      case 401 -> "UNAUTHENTICATED";
      case 403 -> "ACCESS_DENIED";
      case 404 -> "NOT_FOUND";
      case 409 -> "CONFLICT";
      case 423 -> "LOCKED";
      case 503 -> CODE_SERVICE_UNAVAILABLE;
      default -> CODE_UNKNOWN;
    };
  }

  /** Structured field-level validation error (matches backend {@code fieldErrors[]}). */
  public record FieldError(@NotNull String field, @NotNull String message) {}
}
