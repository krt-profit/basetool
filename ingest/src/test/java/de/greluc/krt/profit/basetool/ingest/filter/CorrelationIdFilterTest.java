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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for the gateway's MDC owner (REQ-OBS-001/-002): the correlation id is accepted from a
 * safe inbound header or freshly minted, echoed on the response, and both MDC keys are removed
 * again on the way out — including when the chain below explodes.
 */
class CorrelationIdFilterTest {

  private static final String HEADER = "X-Correlation-Id";

  private final CorrelationIdFilter filter =
      new CorrelationIdFilter(TestLoggingProperties.defaults());

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  /** Captures the MDC as the downstream chain sees it, which is the only place it is populated. */
  private static final class MdcCapturingChain implements FilterChain {

    private String correlationId;
    private String userId;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) {
      correlationId = MDC.get("correlationId");
      userId = MDC.get("userId");
    }
  }

  @Test
  void acceptsAndEchoesASafeInboundCorrelationId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/refinery-extract");
    request.addHeader(HEADER, "abc-123_XY.7");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MdcCapturingChain chain = new MdcCapturingChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.correlationId).isEqualTo("abc-123_XY.7");
    assertThat(response.getHeader(HEADER)).isEqualTo("abc-123_XY.7");
  }

  @Test
  void seedsTheUserIdAsAnonymousBecauseSecurityHasNotRunYet() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/refinery-extract");
    MdcCapturingChain chain = new MdcCapturingChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.userId).isEqualTo(CorrelationIdFilter.ANONYMOUS);
  }

  @Test
  void mintsAFreshIdWhenNoHeaderIsPresent() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MdcCapturingChain chain = new MdcCapturingChain();

    filter.doFilter(new MockHttpServletRequest("POST", "/v1/refinery-extract"), response, chain);

    assertThat(chain.correlationId).isNotBlank();
    assertThat(UUID.fromString(chain.correlationId)).isNotNull();
    assertThat(response.getHeader(HEADER)).isEqualTo(chain.correlationId);
  }

  @Test
  void rejectsAnUnsafeInboundIdAndMintsAFreshOneInstead() throws Exception {
    // A CRLF-carrying value would otherwise forge a log line and a response header.
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/refinery-extract");
    request.addHeader(HEADER, "evil\r\nInjected: 1");
    MdcCapturingChain chain = new MdcCapturingChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.correlationId).doesNotContain("evil");
    assertThat(UUID.fromString(chain.correlationId)).isNotNull();
  }

  @Test
  void rejectsAnOverlongInboundIdAndMintsAFreshOneInstead() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/refinery-extract");
    request.addHeader(HEADER, "a".repeat(129));
    MdcCapturingChain chain = new MdcCapturingChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(chain.correlationId).hasSize(36);
  }

  @Test
  void clearsBothMdcKeysAfterTheRequest() throws Exception {
    filter.doFilter(
        new MockHttpServletRequest("POST", "/v1/refinery-extract"),
        new MockHttpServletResponse(),
        new MockFilterChain());

    assertThat(MDC.get("correlationId")).isNull();
    assertThat(MDC.get("userId")).isNull();
  }

  @Test
  void clearsBothMdcKeysEvenWhenTheChainThrows() {
    // Bleed-through onto a pooled or virtual thread is the failure this finally block prevents.
    FilterChain exploding =
        (request, response) -> {
          throw new IllegalStateException("boom");
        };

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    new MockHttpServletRequest("POST", "/v1/refinery-extract"),
                    new MockHttpServletResponse(),
                    exploding))
        .isInstanceOf(IllegalStateException.class);

    assertThat(MDC.get("correlationId")).isNull();
    assertThat(MDC.get("userId")).isNull();
  }
}
