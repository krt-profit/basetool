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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link BackendServiceException}, which parses a backend RFC 7807 Problem+JSON
 * response into the structured value the controller advice maps to a localized error.
 */
class BackendServiceExceptionTest {

  private JsonMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = JsonMapper.builder().build();
  }

  @Nested
  class FromProblemTests {

    @Test
    void wellFormedProblemBody_populatesAllFields() {
      String body =
          "{"
              + "\"type\":\"https://profit-base.online/problems/validation\","
              + "\"title\":\"Bad Request\","
              + "\"status\":400,"
              + "\"detail\":\"name must not be blank\","
              + "\"code\":\"VALIDATION_FAILED\","
              + "\"correlationId\":\"abc-123\","
              + "\"fieldErrors\":["
              + "{\"field\":\"name\",\"message\":\"must not be blank\"},"
              + "{\"field\":\"email\",\"message\":\"must be a valid email\"}"
              + "]}";

      BackendServiceException ex = BackendServiceException.fromProblem(wcre(400, body), mapper);

      assertEquals(400, ex.getStatusCode());
      assertEquals("VALIDATION_FAILED", ex.getProblemCode());
      assertEquals("abc-123", ex.getCorrelationId());
      assertEquals("name must not be blank", ex.getProblemDetail());
      assertEquals(2, ex.getFieldErrors().size());
      assertEquals("name", ex.getFieldErrors().get(0).field());
      assertEquals("must not be blank", ex.getFieldErrors().get(0).message());
      assertTrue(ex.getMessage().contains("400"));
      assertTrue(ex.getMessage().contains("VALIDATION_FAILED"));
    }

    @Test
    void emptyBody_fallsBackToStatusDerivedCode() {
      BackendServiceException ex = BackendServiceException.fromProblem(wcre(404, ""), mapper);

      assertEquals(404, ex.getStatusCode());
      assertEquals("NOT_FOUND", ex.getProblemCode());
      assertNull(ex.getCorrelationId());
      assertNull(ex.getProblemDetail());
      assertTrue(ex.getFieldErrors().isEmpty());
    }

    @Test
    void nullBody_fallsBackToStatusDerivedCode() {
      BackendServiceException ex = BackendServiceException.fromProblem(wcre(503, null), mapper);

      assertEquals(503, ex.getStatusCode());
      assertEquals(BackendServiceException.CODE_SERVICE_UNAVAILABLE, ex.getProblemCode());
    }

    @Test
    void blankBody_fallsBackToStatusDerivedCode() {
      BackendServiceException ex = BackendServiceException.fromProblem(wcre(409, "   "), mapper);

      assertEquals(409, ex.getStatusCode());
      assertEquals("CONFLICT", ex.getProblemCode());
    }

    @Test
    void malformedJsonBody_fallsBackToStatusDerivedCode() {
      BackendServiceException ex =
          BackendServiceException.fromProblem(
              wcre(503, "<html><body>503 service unavailable</body></html>"), mapper);

      assertEquals(503, ex.getStatusCode());
      assertEquals(
          BackendServiceException.CODE_SERVICE_UNAVAILABLE,
          ex.getProblemCode(),
          "non-JSON body must fall back to deriveCodeFromStatus, NOT crash");
    }

    @Test
    void bodyWithoutCodeProperty_keepsDerivedCode() {
      String body = "{\"title\":\"Not Found\",\"detail\":\"x missing\"}";

      BackendServiceException ex = BackendServiceException.fromProblem(wcre(404, body), mapper);

      assertEquals(
          "NOT_FOUND", ex.getProblemCode(), "no `code` in body -> derive from status code");
      assertEquals("x missing", ex.getProblemDetail());
    }

    @Test
    void fieldErrorsWithMissingFieldOrMessage_useEmptyString() {
      String body =
          "{\"fieldErrors\":["
              + "{\"field\":\"name\"},"
              + "{\"message\":\"must not be null\"},"
              + "{}"
              + "]}";

      BackendServiceException ex = BackendServiceException.fromProblem(wcre(400, body), mapper);

      assertEquals(3, ex.getFieldErrors().size());
      assertEquals("name", ex.getFieldErrors().get(0).field());
      assertEquals("", ex.getFieldErrors().get(0).message());
      assertEquals("", ex.getFieldErrors().get(1).field());
      assertEquals("must not be null", ex.getFieldErrors().get(1).message());
      assertEquals("", ex.getFieldErrors().get(2).field());
      assertEquals("", ex.getFieldErrors().get(2).message());
    }

    @Test
    void fieldErrorsThatIsNotAnArray_isIgnored() {
      String body = "{\"fieldErrors\":{\"name\":\"oops\"}}";

      BackendServiceException ex = BackendServiceException.fromProblem(wcre(400, body), mapper);

      assertTrue(ex.getFieldErrors().isEmpty());
    }

    @Test
    void messageFormat_includesStatusAndCode() {
      BackendServiceException ex =
          BackendServiceException.fromProblem(wcre(400, "{\"code\":\"CUSTOM_CODE\"}"), mapper);

      assertTrue(ex.getMessage().contains("400"));
      assertTrue(ex.getMessage().contains("CUSTOM_CODE"));
    }
  }

  @Nested
  class DeriveCodeTests {

    @Test
    void status400_returnsValidationFailed() {
      assertEquals(
          "VALIDATION_FAILED",
          BackendServiceException.fromProblem(wcre(400, ""), mapper).getProblemCode());
    }

    @Test
    void status401_returnsUnauthenticated() {
      assertEquals(
          "UNAUTHENTICATED",
          BackendServiceException.fromProblem(wcre(401, ""), mapper).getProblemCode());
    }

    @Test
    void status403_returnsAccessDenied() {
      assertEquals(
          "ACCESS_DENIED",
          BackendServiceException.fromProblem(wcre(403, ""), mapper).getProblemCode());
    }

    @Test
    void status404_returnsNotFound() {
      assertEquals(
          "NOT_FOUND", BackendServiceException.fromProblem(wcre(404, ""), mapper).getProblemCode());
    }

    @Test
    void status409_returnsConflict() {
      assertEquals(
          "CONFLICT", BackendServiceException.fromProblem(wcre(409, ""), mapper).getProblemCode());
    }

    @Test
    void status423_returnsLocked() {
      assertEquals(
          "LOCKED", BackendServiceException.fromProblem(wcre(423, ""), mapper).getProblemCode());
    }

    @Test
    void status503_returnsServiceUnavailable() {
      assertEquals(
          BackendServiceException.CODE_SERVICE_UNAVAILABLE,
          BackendServiceException.fromProblem(wcre(503, ""), mapper).getProblemCode());
    }

    @Test
    void otherStatus_returnsUnknown() {
      assertEquals(
          BackendServiceException.CODE_UNKNOWN,
          BackendServiceException.fromProblem(wcre(418, ""), mapper).getProblemCode());
      assertEquals(
          BackendServiceException.CODE_UNKNOWN,
          BackendServiceException.fromProblem(wcre(500, ""), mapper).getProblemCode());
    }
  }

  @Nested
  class ReadableErrorMessageTests {

    @Test
    void prefersStoredProblemDetail() {
      BackendServiceException ex =
          new BackendServiceException(
              "the-message",
              null,
              400,
              "VALIDATION_FAILED",
              null,
              List.of(),
              "name must not be blank");

      assertEquals("name must not be blank", ex.getReadableErrorMessage());
    }

    @Test
    void blankProblemDetail_fallsThroughToCauseChain() {
      BackendServiceException ex =
          new BackendServiceException(
              "msg",
              wcre(400, "{\"detail\":\"from cause body\"}"),
              400,
              "VALIDATION_FAILED",
              null,
              List.of(),
              "   ");

      String readable = ex.getReadableErrorMessage();
      assertNotNull(readable);
    }

    @Test
    void noCause_noProblemDetail_fallsBackToExceptionMessage() {
      BackendServiceException ex = new BackendServiceException("fallback-message", null, 500);

      assertEquals("fallback-message", ex.getReadableErrorMessage());
    }

    @Test
    void nonWebClientCause_fallsBackToExceptionMessage() {
      BackendServiceException ex =
          new BackendServiceException(
              "fallback", new RuntimeException("unrelated"), 500, "X", null, List.of(), null);

      assertEquals("fallback", ex.getReadableErrorMessage());
    }
  }

  @Nested
  class ProblemTypeTests {

    @Test
    void noCause_returnsNull() {
      BackendServiceException ex = new BackendServiceException("x", null, 500);

      assertNull(ex.getProblemType());
    }

    @Test
    void nonWebClientCause_returnsNull() {
      BackendServiceException ex = new BackendServiceException("x", new RuntimeException(), 500);

      assertNull(ex.getProblemType());
    }

    @Test
    void webClientCauseWithoutBody_returnsNullSilently() {
      BackendServiceException ex = new BackendServiceException("x", wcre(500, ""), 500);

      assertNull(ex.getProblemType());
    }
  }

  @Test
  void fieldErrorsList_isImmutableCopy() {
    java.util.ArrayList<BackendServiceException.FieldError> mutable = new java.util.ArrayList<>();
    mutable.add(new BackendServiceException.FieldError("x", "y"));

    BackendServiceException ex =
        new BackendServiceException("msg", null, 400, "X", null, mutable, null);

    mutable.add(new BackendServiceException.FieldError("z", "w"));
    assertEquals(1, ex.getFieldErrors().size(), "internal fieldErrors must be a defensive copy");

    assertTrue(
        isUnmodifiable(ex.getFieldErrors()), "getFieldErrors() must return an unmodifiable view");
  }

  private static boolean isUnmodifiable(List<?> list) {
    try {
      list.clear();
      return false;
    } catch (UnsupportedOperationException expected) {
      return true;
    }
  }

  private static WebClientResponseException wcre(int status, String body) {
    byte[] bodyBytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
    return WebClientResponseException.create(
        status,
        HttpStatus.valueOf(status).getReasonPhrase(),
        org.springframework.http.HttpHeaders.EMPTY,
        bodyBytes,
        StandardCharsets.UTF_8,
        null);
  }
}
