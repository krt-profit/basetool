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

package de.greluc.krt.profit.basetool.ingest.filter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.config.RateLimitProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for the pre-security filters: the payload size cap (REQ-INGEST-005) and the per-IP
 * rate limit (REQ-INGEST-005), including the {@code application/problem+json} body they emit.
 */
class FiltersTest {

  private final JsonMapper objectMapper = JsonMapper.builder().build();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private static MockHttpServletRequest ingestRequestWithBody(int bodyLength) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/refinery-extract");
    request.setContent(new byte[bodyLength]);
    return request;
  }

  /**
   * A request that carries a body but reports no {@code Content-Length} — the shape of a {@code
   * Transfer-Encoding: chunked} request, used to exercise the size filter's streaming guard.
   *
   * @param bodyLength the actual body size served via the input stream
   * @return a mock request whose {@code getContentLength*} return {@code -1}
   */
  private static MockHttpServletRequest chunkedIngestRequest(int bodyLength) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/v1/refinery-extract") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }

          @Override
          public int getContentLength() {
            return -1;
          }
        };
    request.setContent(new byte[bodyLength]);
    return request;
  }

  @Test
  void sizeFilterRejectsOversizedPayloadWith413() throws Exception {
    IngestProperties properties = new IngestProperties();
    properties.setMaxPayloadBytes(10);
    PayloadSizeLimitFilter filter =
        new PayloadSizeLimitFilter(properties, objectMapper, meterRegistry);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(ingestRequestWithBody(100), response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    assertThat(chain.getRequest()).isNull();
    // REQ-OBS-011: the 413 reject increments the (previously absent) detection counter.
    assertThat(meterRegistry.counter(MetricNames.INGEST_PAYLOAD_REJECTED).count()).isEqualTo(1.0);
  }

  @Test
  void sizeFilterPassesPayloadWithinLimit() throws Exception {
    IngestProperties properties = new IngestProperties();
    properties.setMaxPayloadBytes(1024);
    PayloadSizeLimitFilter filter =
        new PayloadSizeLimitFilter(properties, objectMapper, meterRegistry);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(ingestRequestWithBody(50), response, chain);

    assertThat(chain.getRequest()).isNotNull();
  }

  @Test
  void sizeFilterRejectsOversizedChunkedPayloadWith413() throws Exception {
    // INGEST-DOS-1: a chunked body (no Content-Length) over the cap must still be rejected.
    IngestProperties properties = new IngestProperties();
    properties.setMaxPayloadBytes(10);
    PayloadSizeLimitFilter filter =
        new PayloadSizeLimitFilter(properties, objectMapper, meterRegistry);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(chunkedIngestRequest(100), response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void sizeFilterPassesChunkedPayloadWithinLimitAndReplaysBody() throws Exception {
    IngestProperties properties = new IngestProperties();
    properties.setMaxPayloadBytes(1024);
    PayloadSizeLimitFilter filter =
        new PayloadSizeLimitFilter(properties, objectMapper, meterRegistry);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(chunkedIngestRequest(50), response, chain);

    // The within-cap chunked body is buffered and re-served unchanged to the controller.
    assertThat(chain.getRequest()).isNotNull();
    assertThat(chain.getRequest().getInputStream().readAllBytes()).hasSize(50);
  }

  @Test
  void sizeFilterLogsBothSizesSoATooLowCapIsDistinguishableFromAHostileBody() throws Exception {
    IngestProperties properties = new IngestProperties();
    properties.setMaxPayloadBytes(10);
    PayloadSizeLimitFilter filter =
        new PayloadSizeLimitFilter(properties, objectMapper, meterRegistry);

    List<ILoggingEvent> events =
        LogCapture.capture(
            PayloadSizeLimitFilter.class,
            Level.DEBUG,
            () ->
                filter.doFilter(
                    ingestRequestWithBody(100),
                    new MockHttpServletResponse(),
                    new MockFilterChain()));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getFormattedMessage())
        .isEqualTo("Ingest payload rejected: declared=100 bytes exceeds max=10 bytes");
  }

  @Test
  void sizeFilterReportsAChunkedRejectAsDeclaredMinusOne() throws Exception {
    // The stream is abandoned the moment the cap is crossed, so -1 is the honest value — and it is
    // itself the diagnostic: the body arrived without a Content-Length.
    IngestProperties properties = new IngestProperties();
    properties.setMaxPayloadBytes(10);
    PayloadSizeLimitFilter filter =
        new PayloadSizeLimitFilter(properties, objectMapper, meterRegistry);

    List<ILoggingEvent> events =
        LogCapture.capture(
            PayloadSizeLimitFilter.class,
            Level.DEBUG,
            () ->
                filter.doFilter(
                    chunkedIngestRequest(100),
                    new MockHttpServletResponse(),
                    new MockFilterChain()));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getFormattedMessage()).contains("declared=-1 bytes");
  }

  @Test
  void rateLimitFilterLogsThePerIpRejectAtDebugWithoutTheClientAddress() throws Exception {
    // DEBUG, not WARN: an attacker decides how often the pre-auth limiter fires, so a higher level
    // would be a log-flood vector. The client IP stays out — app logs are PII-free (REQ-OBS-004).
    RateLimitProperties properties = new RateLimitProperties();
    properties.setEnabled(true);
    properties.setCapacity(1);
    properties.setRefillTokens(1);
    properties.setRefillPeriod(Duration.ofMinutes(1));
    RateLimitingFilter filter =
        new RateLimitingFilter(properties, objectMapper, new SimpleMeterRegistry());
    filter.doFilter(
        ingestRequestWithBody(10), new MockHttpServletResponse(), new MockFilterChain());

    List<ILoggingEvent> events =
        LogCapture.capture(
            RateLimitingFilter.class,
            Level.DEBUG,
            () ->
                filter.doFilter(
                    ingestRequestWithBody(10),
                    new MockHttpServletResponse(),
                    new MockFilterChain()));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.DEBUG);
    assertThat(events.getFirst().getFormattedMessage())
        .contains("capacity=1")
        .contains("retryAfter=")
        .doesNotContain("127.0.0.1");
  }

  @Test
  void rateLimitFilterAllowsFirstThenBlocksWith429() throws Exception {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setEnabled(true);
    properties.setCapacity(1);
    properties.setRefillTokens(1);
    properties.setRefillPeriod(Duration.ofMinutes(1));
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    RateLimitingFilter filter = new RateLimitingFilter(properties, objectMapper, meterRegistry);

    MockFilterChain firstChain = new MockFilterChain();
    filter.doFilter(ingestRequestWithBody(10), new MockHttpServletResponse(), firstChain);
    assertThat(firstChain.getRequest()).isNotNull();

    MockHttpServletResponse blocked = new MockHttpServletResponse();
    MockFilterChain secondChain = new MockFilterChain();
    filter.doFilter(ingestRequestWithBody(10), blocked, secondChain);

    assertThat(blocked.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    assertThat(blocked.getContentAsString()).contains("RATE_LIMITED");
    assertThat(blocked.getHeader("Retry-After")).isNotNull();
    assertThat(secondChain.getRequest()).isNull();
    // The pre-auth rejection is counted once under the bounded `ip` bucket (REQ-OBS-011).
    assertThat(
            meterRegistry
                .get(MetricNames.RATELIMIT_REJECTIONS)
                .tag(MetricNames.TAG_BUCKET, MetricNames.BUCKET_IP)
                .counter()
                .count())
        .isEqualTo(1.0d);
  }
}
