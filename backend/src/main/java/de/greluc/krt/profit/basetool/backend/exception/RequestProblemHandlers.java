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

import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingMatrixVariableException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import tools.jackson.databind.DatabindException;

/**
 * Handlers for the 4xx outcomes of a malformed request: validation, an unreadable body, a parameter
 * that does not convert, a missing value, an unsupported or unacceptable media type, an oversized
 * upload and an unsupported method.
 */
@Slf4j(topic = ProblemSupport.LOGGER_NAME)
public abstract class RequestProblemHandlers extends SecurityProblemHandlers {

  /**
   * Creates the handler family over the collaborators every problem response needs.
   *
   * @param problemProperties the problem type base URI
   * @param problemResponseFactory the factory that fills the standard problem fields
   * @param messageSource the bundle for the localized title and detail
   * @param meterRegistry the registry for the error counters
   */
  RequestProblemHandlers(
      AppProblemProperties problemProperties,
      ProblemResponseFactory problemResponseFactory,
      MessageSource messageSource,
      MeterRegistry meterRegistry) {
    super(problemProperties, problemResponseFactory, messageSource, meterRegistry);
  }

  /**
   * Maps a failed {@code @Valid @RequestBody} to 400 with code {@code VALIDATION_FAILED}, exposing
   * the field errors as an {@code errors} map and a {@code fieldErrors} list. Rejected values are
   * never logged.
   *
   * @param ex Spring's bind-result wrapper with the field violations
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleValidationExceptions(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Map<String, String> errorsByField = new HashMap<>();
    List<Map<String, String>> errors = new ArrayList<>();
    List<String> logSummary = new ArrayList<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(
            fieldError -> {
              String field = fieldError.getField();
              String message = fieldError.getDefaultMessage();
              errorsByField.put(field, message);
              Map<String, String> entry = new HashMap<>();
              entry.put("field", field);
              entry.put("message", message);
              errors.add(entry);
              logSummary.add(field + "=" + message + " (code=" + fieldError.getCode() + ")");
            });
    ex.getBindingResult()
        .getGlobalErrors()
        .forEach(
            globalError ->
                logSummary.add(
                    "[" + globalError.getObjectName() + "] " + globalError.getDefaultMessage()));
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.validation_failed.title"),
            tr("problem.validation_failed.detail"),
            request,
            "constraint-violation",
            CODE_VALIDATION_FAILED);
    pd.setProperty("errors", errorsByField);
    pd.setProperty("fieldErrors", errors);
    log.warn(
        "Validation failed for {} {} [correlationId={}]: {}",
        request.getMethod(),
        request.getRequestURI(),
        pd.getProperties() != null ? pd.getProperties().get("correlationId") : null,
        logSummary);
    return toEntity(pd);
  }

  /**
   * Maps a Jakarta constraint violation on parameters or service methods to 400 with code {@code
   * CONSTRAINT_VIOLATION}, with the same {@code fieldErrors} list as {@link
   * #handleValidationExceptions}; {@code field} is the property path (e.g. {@code method.arg}).
   *
   * @param ex thrown {@link ConstraintViolationException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<Map<String, String>> errors = new ArrayList<>();
    List<String> logSummary = new ArrayList<>();
    ex.getConstraintViolations()
        .forEach(
            v -> {
              String field = v.getPropertyPath() != null ? v.getPropertyPath().toString() : "";
              Map<String, String> entry = new HashMap<>();
              entry.put("field", field);
              entry.put("message", v.getMessage());
              errors.add(entry);
              logSummary.add(field + "=" + v.getMessage());
            });
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.constraint_violation.title"),
            tr("problem.constraint_violation.detail"),
            request,
            "constraint-violation",
            CODE_CONSTRAINT_VIOLATION);
    pd.setProperty("fieldErrors", errors);
    log.warn(
        "Constraint violation for {} {} [correlationId={}]: {}",
        request.getMethod(),
        request.getRequestURI(),
        pd.getProperties() != null ? pd.getProperties().get("correlationId") : null,
        logSummary);
    return toEntity(pd);
  }

  /**
   * Maps an unreadable request body to 400 with code {@code BAD_REQUEST}, logging the JSON path of
   * the offending node but no user values.
   *
   * @param ex Spring's wrapper around the parse failure
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> handleHttpMessageNotReadable(
      @NotNull HttpMessageNotReadableException ex, HttpServletRequest request) {
    Throwable rootCause = ex.getMostSpecificCause();
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.unreadable_body.title"),
            tr("problem.unreadable_body.detail"),
            request,
            "bad-request",
            CODE_BAD_REQUEST);
    Map<String, Object> extra = new HashMap<>();
    extra.put("contentType", String.valueOf(request.getContentType()));
    if (rootCause != null) {
      extra.put("rootCause", rootCause.getClass().getSimpleName());
      extra.put("causeMessage", maskQuotedValues(rootCause.getMessage()));
      if (rootCause instanceof DatabindException jme && jme.getPath() != null) {
        StringBuilder path = new StringBuilder();
        jme.getPath()
            .forEach(
                ref -> {
                  if (ref.getPropertyName() != null) {
                    if (path.length() > 0) {
                      path.append('.');
                    }
                    path.append(ref.getPropertyName());
                  } else if (ref.getIndex() >= 0) {
                    path.append('[').append(ref.getIndex()).append(']');
                  }
                });
        if (path.length() > 0) {
          extra.put("jsonPath", path.toString());
        }
      }
    }
    logProblem(request, pd, "Unreadable request body", extra);
    return toEntity(pd);
  }

  /**
   * Replaces every double-quoted segment of a Jackson parse message with {@code "***"}, removing
   * the rejected user value while keeping the structural text (REQ-OBS-004).
   *
   * @param message the parse cause's message; may be {@code null}
   * @return the masked message, or {@code null} when {@code message} is {@code null}
   */
  @Contract("null -> null")
  @Nullable
  static String maskQuotedValues(String message) {
    if (message == null) {
      return null;
    }
    return message.replaceAll("\"[^\"]*\"", "\"***\"");
  }

  /**
   * Maps a path or query parameter conversion failure to 400 with code {@code TYPE_MISMATCH},
   * logging only the parameter name and target type, never the raw value.
   *
   * @param ex Spring's binder wrapper for the conversion failure
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> handleTypeMismatch(
      @NotNull MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.type_mismatch.title"),
            tr("problem.type_mismatch.detail", ex.getName()),
            request,
            "type-mismatch",
            CODE_TYPE_MISMATCH);
    Map<String, Object> extra = new HashMap<>();
    extra.put("parameter", ex.getName());
    extra.put(
        "targetType", ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "n/a");
    logProblem(request, pd, "Type mismatch", extra);
    return toEntity(pd);
  }

  /**
   * Maps a request body in an unreadable media type (e.g. {@code application/cbor}, which is
   * response-only) to 415 (REQ-API-011).
   *
   * @param ex the refusal, carrying the offending and the supported content types
   * @param request the request, for the {@code instance} URI and the correlation id
   * @return a {@code 415} RFC 7807 problem naming the refused type
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMediaTypeNotSupported(
      @NotNull HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            tr("problem.unsupported_media_type.title"),
            tr("problem.unsupported_media_type.detail", String.valueOf(ex.getContentType())),
            request,
            "unsupported-media-type",
            CODE_UNSUPPORTED_MEDIA_TYPE);
    logProblem(
        request,
        pd,
        "Unsupported media type",
        Map.of("supportedMediaTypes", String.valueOf(ex.getSupportedMediaTypes())));
    return toEntity(pd);
  }

  /**
   * Maps a response no media type the request accepts can carry to 406 with code {@code
   * NOT_ACCEPTABLE}, logging the producible types.
   *
   * @param ex the refusal, carrying the producible media types
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ProblemDetail> handleMediaTypeNotAcceptable(
      @NotNull HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.NOT_ACCEPTABLE,
            tr("problem.not_acceptable.title"),
            tr("problem.not_acceptable.detail"),
            request,
            "not-acceptable",
            CODE_NOT_ACCEPTABLE);
    logProblem(
        request,
        pd,
        "Not acceptable",
        Map.of("supportedMediaTypes", String.valueOf(ex.getSupportedMediaTypes())));
    return toEntity(pd);
  }

  /**
   * Maps a missing request parameter, header, cookie, matrix variable or multipart part, and a
   * mapping's unsatisfied parameter condition, to 400 with code {@code BAD_REQUEST}, the detail
   * naming the missing value. One Spring classifies as a server error, a path variable the mapping
   * does not declare, stays the generic 500 of {@link #handleAllExceptions}.
   *
   * @param ex a {@link ServletRequestBindingException} or {@link
   *     MissingServletRequestPartException}
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler({
    ServletRequestBindingException.class,
    MissingServletRequestPartException.class
  })
  public ResponseEntity<ProblemDetail> handleMissingRequestValue(
      @NotNull Exception ex, HttpServletRequest request) {
    if (ex instanceof ErrorResponse response && response.getStatusCode().is5xxServerError()) {
      return handleAllExceptions(ex, request);
    }
    String name = missingValueName(ex);
    ProblemDetail pd =
        problem(
            HttpStatus.BAD_REQUEST,
            tr("problem.bad_request.title"),
            name != null
                ? tr("problem.missing_request_value.detail", name)
                : tr("problem.bad_request.detail"),
            request,
            "bad-request",
            CODE_BAD_REQUEST);
    logProblem(
        request,
        pd,
        "Missing request value",
        Map.of("exception", ex.getClass().getSimpleName(), "name", String.valueOf(name)));
    return toEntity(pd);
  }

  /**
   * Names the value a binding exception found missing.
   *
   * @param ex the binding exception
   * @return the parameter, header, cookie, variable or part name, or {@code null} when the
   *     exception names none
   */
  @Nullable
  private static String missingValueName(@NotNull Exception ex) {
    return switch (ex) {
      case MissingServletRequestParameterException missing -> missing.getParameterName();
      case MissingRequestHeaderException missing -> missing.getHeaderName();
      case MissingRequestCookieException missing -> missing.getCookieName();
      case MissingPathVariableException missing -> missing.getVariableName();
      case MissingMatrixVariableException missing -> missing.getVariableName();
      case MissingServletRequestPartException missing -> missing.getRequestPartName();
      default -> null;
    };
  }

  /**
   * Maps an upload over the multipart size limit to 413 with code {@code REQUEST_BODY_TOO_LARGE}.
   *
   * @param ex the multipart resolver's refusal
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ProblemDetail> handleMaxUploadSizeExceeded(
      @NotNull MaxUploadSizeExceededException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.CONTENT_TOO_LARGE,
            tr("problem.request_body_too_large.title"),
            tr("problem.request_body_too_large.detail"),
            request,
            "request-body-too-large",
            CODE_REQUEST_BODY_TOO_LARGE);
    logProblem(request, pd, "Upload too large", Map.of("maxUploadSize", ex.getMaxUploadSize()));
    return toEntity(pd);
  }

  /**
   * Maps an unsupported HTTP method to 405 with code {@code METHOD_NOT_ALLOWED}, logging the
   * supported methods.
   *
   * @param ex Spring's method-not-supported exception
   * @param request servlet request for instance URI + access-log enrichment
   * @return RFC 7807 problem-detail response
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethodNotSupported(
      @NotNull HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    ProblemDetail pd =
        problem(
            HttpStatus.METHOD_NOT_ALLOWED,
            tr("problem.method_not_allowed.title"),
            tr("problem.method_not_allowed.detail", ex.getMethod()),
            request,
            "method-not-allowed",
            CODE_METHOD_NOT_ALLOWED);
    logProblem(
        request,
        pd,
        "Method not allowed",
        Map.of(
            "supportedMethods",
            String.valueOf(
                Arrays.toString(
                    ex.getSupportedMethods() == null ? new String[0] : ex.getSupportedMethods()))));
    return toEntity(pd);
  }
}
