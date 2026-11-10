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

package de.greluc.krt.profit.basetool.frontend.bank.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.frontend.bank.client.BankBackendClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

/** Verifies streamed bank downloads and status propagation before the response is committed. */
class BankReportProxyControllerTest {

  private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
  private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-02-01T00:00:00Z");
  private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46};

  private BackendClientHarness backend;
  private BankReportProxyController controller;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    controller = new BankReportProxyController(new BankBackendClient(backend.backendApiClient()));
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void streamsStatementBytesAndPreservesTheDownloadContract() throws Exception {
    backend.answerBytes("application/pdf", PDF);
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.downloadStatement(ID, FROM, TO, "Europe/Berlin", response);

    assertThat(response.getContentAsByteArray()).isEqualTo(PDF);
    assertThat(response.getContentType()).isEqualTo("application/pdf");
    assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
        .contains("attachment", "kontoauszug-" + ID + ".pdf");
    assertThat(
            backend
                .expect(
                    "GET", "/api/v1/bank/accounts/" + ID + "/statement?from=" + FROM + "&to=" + TO)
                .getHeader("X-User-Time-Zone"))
        .isEqualTo("Europe/Berlin");
  }

  @Test
  void aRefusalPreservesItsStatusBeforeExposingAnyDownloadBytes() {
    backend.answerProblem(403, "FORBIDDEN");
    MockHttpServletResponse response = new MockHttpServletResponse();

    assertThatThrownBy(() -> controller.downloadStatement(ID, FROM, TO, null, response))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    assertThat(response.isCommitted()).isFalse();
    assertThat(response.getContentAsByteArray()).isEmpty();
    assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
  }

  @Test
  void streamsTheThreeMonthReportWithItsFixedFilename() throws Exception {
    backend.answerBytes("application/pdf", PDF);
    MockHttpServletResponse response = new MockHttpServletResponse();

    controller.downloadThreeMonthReport(null, response);

    assertThat(response.getContentAsByteArray()).isEqualTo(PDF);
    assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
        .contains("bank-3-monats-report.pdf");
    backend.expect("GET", "/api/v1/bank/export/three-month-report");
  }

  @Test
  void aBodyTimeoutBeforeCommitClearsDownloadHeadersAndKeepsSecurityHeaders() throws Exception {
    try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer()) {
      server.start();
      server.enqueue(
          new okhttp3.mockwebserver.MockResponse()
              .setHeader("Content-Type", "application/pdf")
              .setBody(new okio.Buffer().write(PDF))
              .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS));
      var properties =
          new de.greluc.krt.profit.basetool.frontend.kernel.backend.AppHttpProperties(
              java.time.Duration.ofSeconds(3),
              java.time.Duration.ofSeconds(30),
              java.time.Duration.ofSeconds(1),
              java.time.Duration.ofSeconds(30),
              java.time.Duration.ofSeconds(30),
              de.greluc.krt.profit.basetool.frontend.kernel.backend.AppHttpProperties
                  .BackendProtocol.HTTP11,
              20,
              de.greluc.krt.profit.basetool.frontend.kernel.backend.AppHttpProperties.BackendCodec
                  .JSON,
              true);
      var policy =
          new de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendCallPolicy(
              io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults(),
              io.github.resilience4j.retry.RetryRegistry.ofDefaults(),
              io.github.resilience4j.timelimiter.TimeLimiterRegistry.ofDefaults(),
              io.github.resilience4j.bulkhead.BulkheadRegistry.ofDefaults(),
              properties);
      var transport =
          org.springframework.web.reactive.function.client.WebClient.builder()
              .baseUrl(server.url("/").toString())
              .build();
      var kernel =
          new de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient(
              transport,
              transport,
              new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
              new org.springframework.cache.support.NoOpCacheManager(),
              policy);
      var timed = new BankReportProxyController(new BankBackendClient(kernel));
      java.util.concurrent.atomic.AtomicBoolean acceptedHeaders =
          new java.util.concurrent.atomic.AtomicBoolean();
      MockHttpServletResponse response =
          new MockHttpServletResponse() {
            @Override
            public void setContentType(String type) {
              if ("application/pdf".equals(type)) {
                acceptedHeaders.set(true);
              }
              super.setContentType(type);
            }
          };
      response.setHeader("X-Security-Test", "keep");

      assertThatThrownBy(() -> timed.downloadStatement(ID, FROM, TO, null, response))
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));

      assertThat(acceptedHeaders.get()).isTrue();
      assertThat(response.getContentAsByteArray()).isEmpty();
      assertThat(response.getContentType()).isNull();
      assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
      assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH)).isNull();
      assertThat(response.getHeader("X-Security-Test")).isEqualTo("keep");
    }
  }
}
