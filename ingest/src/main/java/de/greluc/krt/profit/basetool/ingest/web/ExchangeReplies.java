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
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Builds the answers of the exchange routes: a relayed answer that holds its contract, a JSON
 * document, and the problems a route refuses with (REQ-XCH-025).
 */
@Component
@RequiredArgsConstructor
class ExchangeReplies {

  private final LoggingProperties loggingProperties;
  private final ExchangeAnswers answers;

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
    if (!result.isOk()) {
      return problem(result.status(), result.code(), result.detail());
    }
    JsonNode body = result.body();
    answers.addUnknownFieldWarnings(body, responseSchema, unknown);
    return answers.passesRelayed(body, responseSchema, request, context) ? ok(body) : failed();
  }

  /**
   * Answers with a JSON document.
   *
   * @param body the document
   * @return {@code 200}
   */
  @NotNull
  ResponseEntity<?> ok(@NotNull JsonNode body) {
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
