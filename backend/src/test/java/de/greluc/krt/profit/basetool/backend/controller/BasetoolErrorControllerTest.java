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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link BasetoolErrorController}: container error dispatches render an
 * RFC&nbsp;7807 problem+json body with the status-derived code, a correlation id (body + header)
 * and the original request URI as {@code instance}, defaulting to 500 when no status attribute is
 * present.
 */
class BasetoolErrorControllerTest {

  private BasetoolErrorController controller;

  @BeforeEach
  void setUp() {
    // Return the caller-supplied default (arg 2 == the bundle key here) so assertions are stable.
    MessageSource messageSource = mock(MessageSource.class);
    when(messageSource.getMessage(anyString(), any(), anyString(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    controller =
        new BasetoolErrorController(
            new ProblemResponseFactory(new AppProblemProperties()), messageSource);
  }

  private static MockHttpServletRequest errorDispatch(Integer statusCode, String originalUri) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
    if (statusCode != null) {
      request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, statusCode);
    }
    if (originalUri != null) {
      request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
    }
    return request;
  }

  @Test
  void notFound_rendersProblemJsonWithNotFoundCode() {
    ResponseEntity<ProblemDetail> response =
        controller.handleError(errorDispatch(404, "/api/v1/does-not-exist"));

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
    ProblemDetail body = response.getBody();
    assertNotNull(body);
    assertEquals(404, body.getStatus());
    assertEquals("NOT_FOUND", body.getProperties().get("code"));
    assertNotNull(body.getInstance());
    assertEquals("/api/v1/does-not-exist", body.getInstance().toString());
    assertTrue(
        body.getType().toString().endsWith("/not-found"),
        "type URI must be built off AppProblemProperties.baseUri");
  }

  @Test
  void error_carriesCorrelationIdInBodyAndHeader() {
    ResponseEntity<ProblemDetail> response =
        controller.handleError(errorDispatch(500, "/api/v1/boom"));

    String header = response.getHeaders().getFirst("X-Correlation-Id");
    assertNotNull(header, "the error response must echo X-Correlation-Id");
    ProblemDetail body = response.getBody();
    assertNotNull(body);
    assertEquals(
        header,
        body.getProperties().get("correlationId"),
        "the X-Correlation-Id header must match the body correlationId");
  }

  @Test
  void missingStatusAttribute_defaultsToInternalServerError() {
    ResponseEntity<ProblemDetail> response = controller.handleError(errorDispatch(null, null));

    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    ProblemDetail body = response.getBody();
    assertNotNull(body);
    assertEquals("INTERNAL_ERROR", body.getProperties().get("code"));
  }

  @Test
  void methodNotAllowed_mapsToMethodNotAllowedCode() {
    ResponseEntity<ProblemDetail> response =
        controller.handleError(errorDispatch(405, "/api/v1/missions"));

    assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
    ProblemDetail body = response.getBody();
    assertNotNull(body);
    assertEquals("METHOD_NOT_ALLOWED", body.getProperties().get("code"));
  }
}
