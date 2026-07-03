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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Verifies that service-level "not found" exceptions are translated into a proper RFC7807 404
 * response instead of bubbling up into the generic 500 handler.
 */
class GlobalExceptionHandlerNotFoundTest {

  private GlobalExceptionHandler handler;
  private HttpServletRequest request;

  @BeforeEach
  void setUp() {
    AppProblemProperties props = new AppProblemProperties();
    props.setBaseUri("https://profit-base.online/problems/");
    ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
    messageSource.setBasename("messages");
    messageSource.setDefaultEncoding("UTF-8");
    messageSource.setFallbackToSystemLocale(false);
    LocaleContextHolder.setLocale(Locale.ENGLISH);
    handler = new GlobalExceptionHandler(props, messageSource, new SimpleMeterRegistry());
    request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/v1/missions/abc");
  }

  @AfterEach
  void tearDown() {
    LocaleContextHolder.resetLocaleContext();
  }

  @Test
  void handleNotFound_shouldReturn404ProblemJson() {
    // Given
    NotFoundException ex = new NotFoundException("Mission not found");

    // When
    ResponseEntity<ProblemDetail> response = handler.handleNotFound(ex, request);

    // Then
    assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
    assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
    ProblemDetail pd = response.getBody();
    assertNotNull(pd);
    assertEquals(404, pd.getStatus());
    assertEquals("Not Found", pd.getTitle());
    assertEquals("Mission not found", pd.getDetail());
    assertEquals(URI.create("https://profit-base.online/problems/not-found"), pd.getType());
    assertEquals(URI.create("/api/v1/missions/abc"), pd.getInstance());
    assertNotNull(pd.getProperties());
    assertEquals(GlobalExceptionHandler.CODE_NOT_FOUND, pd.getProperties().get("code"));
    assertNotNull(pd.getProperties().get("correlationId"));
  }

  @Test
  void handleNotFound_shouldFallBackToGenericDetailIfNull() {
    NotFoundException ex = new NotFoundException(null);

    ResponseEntity<ProblemDetail> response = handler.handleNotFound(ex, request);

    assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
    ProblemDetail pd = response.getBody();
    assertNotNull(pd);
    assertEquals("The requested resource was not found.", pd.getDetail());
  }
}
