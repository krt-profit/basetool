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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the pre-MVC problem writer. The filters that use it (size cap, rate limit, IdP
 * unavailable) run before Spring MVC, so this class is the only thing standing between a
 * short-circuited request and an untyped container error page — its output must be shaped exactly
 * like a controller-level problem (REQ-API-*, REQ-OBS-002).
 */
class ProblemResponseWriterTest {

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private MockHttpServletResponse write() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    ProblemResponseWriter.write(
        response,
        objectMapper,
        TestLoggingProperties.defaults(),
        HttpStatus.TOO_MANY_REQUESTS,
        "Rate limit exceeded",
        "RATE_LIMITED",
        "Too many ingest requests. Please retry later.");
    return response;
  }

  @Test
  void writesAnRfc7807BodyWithTheStableCode() throws Exception {
    MockHttpServletResponse response = write();

    assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
    assertThat(response.getContentAsString())
        .contains("\"code\":\"RATE_LIMITED\"")
        .contains("\"title\":\"Rate limit exceeded\"")
        .contains("\"status\":429");
  }

  @Test
  void carriesTheCurrentCorrelationIdSoTheBodyAndTheLogLineMatch() throws Exception {
    MDC.put("correlationId", "cid-42");

    assertThat(write().getContentAsString()).contains("\"correlationId\":\"cid-42\"");
  }

  /**
   * The MDC key is read from {@code LoggingProperties}, not hard-coded (ING-SEC-03 / ING-SIMP-03):
   * with a renamed key the id is still found, and the public member keeps its stable name.
   */
  @Test
  void readsTheCorrelationIdUnderTheConfiguredMdcKey() throws Exception {
    MDC.put("traceKey", "cid-77");
    try {
      MockHttpServletResponse response = new MockHttpServletResponse();
      ProblemResponseWriter.write(
          response,
          objectMapper,
          new LoggingProperties("X-Correlation-Id", "traceKey", "userId", 2000L, 1500L, false),
          HttpStatus.TOO_MANY_REQUESTS,
          "Rate limit exceeded",
          "RATE_LIMITED",
          "Too many ingest requests. Please retry later.");

      assertThat(response.getContentAsString()).contains("\"correlationId\":\"cid-77\"");
    } finally {
      MDC.remove("traceKey");
    }
  }

  @Test
  void omitsTheCorrelationIdWhenTheMdcIsEmpty() throws Exception {
    MDC.remove("correlationId");

    assertThat(write().getContentAsString()).doesNotContain("correlationId");
  }
}
