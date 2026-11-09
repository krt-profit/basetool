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

package de.greluc.krt.profit.basetool.ingest.web;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.contract.ExchangeSchemas;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.problem.Problems;
import de.greluc.krt.profit.basetool.ingest.relay.ExchangeRelay;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Relays the gateway's reads and single-body writes and shapes every answer a route sends: the
 * checked page request or body, a usable backend answer that holds its contract, and the problems
 * of a refusal (REQ-XCH-011, REQ-XCH-025).
 */
@Component
@RequiredArgsConstructor
class ExchangeRelayResponses {

  private static final Pattern CURSOR = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,4}$");

  private final ExchangeRelay relay;
  private final ExchangeAnswers answers;
  private final ExchangeSchemas schemas;
  private final LoggingProperties loggingProperties;

  /**
   * Checks the paging parameters, relays a page request and checks the answer.
   *
   * @param path the route below {@code /exchange/v1}
   * @param responseSchema the page's schema
   * @param cursor the cursor, or {@code null}
   * @param limit the page size, or {@code null}
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  @NotNull
  ResponseEntity<?> page(
      @NotNull String path,
      @NotNull String responseSchema,
      @Nullable String cursor,
      @Nullable String limit,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    return page(path, responseSchema, Map.of(), cursor, limit, request, acceptLanguage);
  }

  /**
   * Checks the paging parameters, relays a page request with further checked query parameters and
   * checks the answer.
   *
   * @param path the route below {@code /exchange/v1}
   * @param responseSchema the page's schema
   * @param query further query parameters, already checked and safe to append
   * @param cursor the cursor, or {@code null}
   * @param limit the page size, or {@code null}
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the page or a problem
   */
  @NotNull
  ResponseEntity<?> page(
      @NotNull String path,
      @NotNull String responseSchema,
      @NotNull Map<String, String> query,
      @Nullable String cursor,
      @Nullable String limit,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    if (limit != null && !validLimit(limit)) {
      return schemaInvalid(
          List.of(
              new ExchangeSchemas.Violation(
                  "/limit", "must be an integer from 1 to " + ExchangeController.PAGE_MAX_LIMIT)));
    }
    if (cursor != null && !CURSOR.matcher(cursor).matches()) {
      return problem(
          HttpStatus.GONE.value(),
          ExchangeController.CURSOR_EXPIRED,
          "The cursor is not one the server issued.");
    }
    StringBuilder target = new StringBuilder(ExchangeController.BACKEND).append(path);
    char separator = '?';
    for (Map.Entry<String, String> parameter : new TreeMap<>(query).entrySet()) {
      target.append(separator).append(parameter.getKey()).append('=').append(parameter.getValue());
      separator = '&';
    }
    if (cursor != null) {
      target.append(separator).append("cursor=").append(cursor);
      separator = '&';
    }
    if (limit != null) {
      target.append(separator).append("limit=").append(Integer.parseInt(limit));
    }
    return relayed(
        relay.forward(HttpMethod.GET, target.toString(), null, context, acceptLanguage),
        responseSchema,
        List.of(),
        context,
        request);
  }

  /**
   * Checks a body, relays it and checks the answer, which reaches the client with {@code 200}.
   *
   * @param method the method on both sides
   * @param body the request body
   * @param requestSchema the body's schema
   * @param path the route below {@code /exchange/v1}
   * @param responseSchema the answer's schema
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the answer or a problem
   */
  @NotNull
  ResponseEntity<?> body(
      @NotNull HttpMethod method,
      @NotNull JsonNode body,
      @NotNull String requestSchema,
      @NotNull String path,
      @NotNull String responseSchema,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    return body(
        method, body, requestSchema, path, responseSchema, HttpStatus.OK, request, acceptLanguage);
  }

  /**
   * Checks a body, relays it and checks the answer, which reaches the client with the given status.
   *
   * @param method the method on both sides
   * @param body the request body
   * @param requestSchema the body's schema
   * @param path the route below {@code /exchange/v1}
   * @param responseSchema the answer's schema
   * @param success the status a usable answer reaches the client with
   * @param request the admitted request
   * @param acceptLanguage the caller's language
   * @return the answer or a problem
   */
  @NotNull
  ResponseEntity<?> body(
      @NotNull HttpMethod method,
      @NotNull JsonNode body,
      @NotNull String requestSchema,
      @NotNull String path,
      @NotNull String responseSchema,
      @NotNull HttpStatus success,
      @NotNull HttpServletRequest request,
      @Nullable String acceptLanguage) {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    if (context == null) {
      return failed();
    }
    List<ExchangeSchemas.Violation> violations = schemas.validate(requestSchema, body);
    if (!violations.isEmpty()) {
      return schemaInvalid(violations);
    }
    List<String> unknown = schemas.unknownFields(requestSchema, body);
    ResponseEntity<?> unreportable = unreportable(unknown);
    if (unreportable != null) {
      return unreportable;
    }
    return relayed(
        relay.forward(method, ExchangeController.BACKEND + path, body, context, acceptLanguage),
        responseSchema,
        unknown,
        success,
        context,
        request);
  }

  /**
   * Refuses a body whose undeclared field could not be reported as a warning, because its JSON
   * Pointer exceeds {@link ExchangeSchemas#MAX_POINTER} characters; checked before the relay, so a
   * write the backend committed always gets an answer that matches its schema.
   *
   * @param unknown the request's undeclared fields
   * @return {@code 400 SCHEMA_INVALID} naming the fields' parents, or {@code null} when every field
   *     can be reported
   */
  @Nullable
  ResponseEntity<?> unreportable(@NotNull List<String> unknown) {
    List<ExchangeSchemas.Violation> violations =
        unknown.stream()
            .filter(pointer -> pointer.length() > ExchangeSchemas.MAX_POINTER)
            .map(pointer ->
                new ExchangeSchemas.Violation(
                    ExchangeSchemas.reportable(pointer), "holds a property name too long"))
            .distinct()
            .toList();
    return violations.isEmpty() ? null : schemaInvalid(violations);
  }

  /**
   * Whether a page size lies within the contract.
   *
   * @param limit the raw value
   * @return {@code true} for an integer from 1 to {@value ExchangeController#PAGE_MAX_LIMIT}
   */
  static boolean validLimit(@NotNull String limit) {
    if (!LIMIT.matcher(limit).matches()) {
      return false;
    }
    int value = Integer.parseInt(limit);
    return value >= 1 && value <= ExchangeController.PAGE_MAX_LIMIT;
  }

  /**
   * Turns a relay result into the answer: a usable one gains the request's unknown-field warnings
   * and reaches the client only when it holds its contract.
   *
   * @param result the relay result
   * @param responseSchema the answer's schema
   * @param unknown the request's undeclared fields
   * @param context what the gate established
   * @param request the admitted request
   * @return the answer or a problem
   */
  @NotNull
  ResponseEntity<?> relayed(
      @NotNull ExchangeRelay.Result result,
      @NotNull String responseSchema,
      @NotNull List<String> unknown,
      @NotNull ExchangeRequestContext context,
      @NotNull HttpServletRequest request) {
    return relayed(result, responseSchema, unknown, HttpStatus.OK, context, request);
  }

  /**
   * Turns a relay result into the answer with the given success status.
   *
   * @param result the relay result
   * @param responseSchema the answer's schema
   * @param unknown the request's undeclared fields
   * @param success the status a usable answer reaches the client with
   * @param context what the gate established
   * @param request the admitted request
   * @return the answer or a problem
   */
  @NotNull
  private ResponseEntity<?> relayed(
      @NotNull ExchangeRelay.Result result,
      @NotNull String responseSchema,
      @NotNull List<String> unknown,
      @NotNull HttpStatus success,
      @NotNull ExchangeRequestContext context,
      @NotNull HttpServletRequest request) {
    if (!result.isOk()) {
      return problem(result.status(), result.code(), result.detail());
    }
    JsonNode body = result.body();
    answers.addUnknownFieldWarnings(body, responseSchema, unknown);
    return answers.passesRelayed(body, responseSchema, request, context)
        ? ResponseEntity.status(success).contentType(MediaType.APPLICATION_JSON).body(body)
        : failed();
  }

  /**
   * Answers with a JSON document.
   *
   * @param body the document
   * @return {@code 200}
   */
  static @NotNull ResponseEntity<?> ok(@NotNull JsonNode body) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
  }

  /**
   * Answers a request body that breaks its schema; every pointer is shortened to one the problem
   * schema can carry.
   *
   * @param violations the violations
   * @return {@code 400 SCHEMA_INVALID} with {@code errors[]}
   */
  @NotNull
  ResponseEntity<?> schemaInvalid(@NotNull List<ExchangeSchemas.Violation> violations) {
    ProblemDetail problem =
        Problems.of(
            loggingProperties,
            HttpStatus.BAD_REQUEST,
            "Bad request",
            "SCHEMA_INVALID",
            "The body does not match the v1 schema.");
    problem.setProperty(
        "errors",
        violations.stream()
            .map(v -> new Problems.FieldError(ExchangeSchemas.reportable(v.pointer()), v.message()))
            .toList());
    return ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  /**
   * Answers a relay failure.
   *
   * @return {@code 502 BACKEND_RELAY_FAILED}
   */
  @NotNull
  ResponseEntity<?> failed() {
    ExchangeRelay.Result failed = ExchangeRelay.Result.failed();
    return problem(failed.status(), failed.code(), failed.detail());
  }

  /**
   * Builds a problem answer, with the {@code Retry-After} the gateway's gate sends for a relayed
   * gate code that carries one.
   *
   * @param status the status
   * @param code the code
   * @param detail the detail
   * @return the answer
   */
  @NotNull
  ResponseEntity<?> problem(int status, @Nullable String code, @Nullable String detail) {
    HttpStatus httpStatus = HttpStatus.valueOf(status);
    ProblemDetail problem =
        Problems.of(
            loggingProperties,
            httpStatus,
            httpStatus.getReasonPhrase(),
            code == null ? ExchangeRelay.RELAY_FAILED : code,
            detail);
    ResponseEntity.BodyBuilder answer =
        ResponseEntity.status(httpStatus).contentType(MediaType.APPLICATION_PROBLEM_JSON);
    String retryAfter = ExchangeRelay.retryAfterSeconds(code);
    if (retryAfter != null) {
      answer.header(HttpHeaders.RETRY_AFTER, retryAfter);
    }
    return answer.body(problem);
  }
}
