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

import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the backend-relay branch of {@link GlobalExceptionHandler} (security audit gap-fill): a
 * backend 4xx must surface only the backend problem's sanitised {@code detail}
 * (content-type-checked + length-capped), never the raw response body, and a backend 5xx must
 * collapse to a generic 502.
 */
class GlobalExceptionHandlerTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final GlobalExceptionHandler handler =
      new GlobalExceptionHandler(JsonMapper.builder().build(), meterRegistry);

  private static WebClientResponseException backendError(
      int status, MediaType contentType, String body) {
    HttpHeaders headers = new HttpHeaders();
    if (contentType != null) {
      headers.setContentType(contentType);
    }
    return WebClientResponseException.create(
        status,
        "status",
        headers,
        body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8);
  }

  @Test
  void backend4xxProblemJson_relaysOnlyTheDetail() {
    WebClientResponseException ex =
        backendError(
            400,
            MediaType.APPLICATION_PROBLEM_JSON,
            "{\"title\":\"Bad Request\",\"detail\":\"Mission is already finalized.\","
                + "\"status\":400,\"code\":\"BUSINESS_CONFLICT\"}");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.getDetail()).isEqualTo("Mission is already finalized.");
    // A backend 4xx reject is counted once under the bounded backend_reject reason (REQ-OBS-011).
    assertThat(
            meterRegistry
                .get(MetricNames.INGEST_HANDOFF_ERRORS)
                .tag(MetricNames.TAG_REASON, MetricNames.REASON_BACKEND_REJECT)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  @Test
  void backend4xxNonProblemJson_doesNotRelayRawBody() {
    WebClientResponseException ex =
        backendError(
            400, MediaType.TEXT_HTML, "<html><body>nginx internal 400 — /admin</body></html>");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.getDetail()).isEqualTo("The import backend rejected the request.");
    assertThat(problem.getDetail()).doesNotContain("nginx", "/admin");
  }

  @Test
  void backend4xxOversizedDetail_isCappedAt500() {
    String longDetail = "x".repeat(2000);
    WebClientResponseException ex =
        backendError(
            422, MediaType.APPLICATION_PROBLEM_JSON, "{\"detail\":\"" + longDetail + "\"}");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getDetail()).hasSize(500);
  }

  @Test
  void backend5xx_collapsesToGeneric502() {
    WebClientResponseException ex =
        backendError(
            500, MediaType.APPLICATION_PROBLEM_JSON, "{\"detail\":\"backend stacktrace boom\"}");

    ProblemDetail problem = handler.handleBackendResponse(ex);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
    assertThat(problem.getDetail()).doesNotContain("boom");
  }
}
