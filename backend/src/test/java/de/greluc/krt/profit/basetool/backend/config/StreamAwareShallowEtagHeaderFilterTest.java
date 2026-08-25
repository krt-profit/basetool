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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * The guard that keeps the ETag buffer off the Server-Sent-Event endpoints (#1653).
 *
 * <p>The defect this pins had no other signal: the plain filter buffers a response to compute its
 * ETag and skips the write-back once async processing has started, so an SSE endpoint answered
 * {@code 200} and then delivered nothing at all, indefinitely, while every connection metric read
 * healthy.
 */
class StreamAwareShallowEtagHeaderFilterTest {

  private final StreamAwareShallowEtagHeaderFilter filter =
      new StreamAwareShallowEtagHeaderFilter();

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/notifications/stream", "/api/v1/live-sync/stream"})
  @DisplayName("a streaming endpoint is not filtered, so its bytes are never buffered")
  void streamingEndpointsBypassTheFilter(String path) {
    assertThat(filter.shouldNotFilter(get(path))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/notifications",
        "/api/v1/notifications/unread-count",
        "/api/v1/live-sync/changed",
        "/api/v1/missions/search",
        "/"
      })
  @DisplayName("everything else keeps its ETag, including the rest of the two families")
  void ordinaryEndpointsAreStillFiltered(String path) {
    // Narrowing to a prefix would have been the easy mistake: the notification family is mostly
    // ordinary reads that benefit from a 304, and only its /stream member must escape.
    assertThat(filter.shouldNotFilter(get(path))).isFalse();
  }

  @Test
  @DisplayName("an unnormalised spelling is NOT recognised, and that is the safe direction")
  void unnormalisedPathsAreNotRecognised() {
    // Written down rather than fixed, because the consequence is bounded and the fix is not free:
    // the match runs on the parsed request URI, which does not collapse dot segments or decode
    // escapes, so `/api/v1/notifications/./stream` is buffered like any other response. What that
    // costs is one broken stream for a client that addressed the endpoint in a way no client of
    // ours does -- it exposes nothing and swallows nothing that would otherwise have been served.
    // The same idiom guards the per-subject rate limiter, so a stricter normalisation belongs
    // there and here together, not in one of them.
    assertThat(filter.shouldNotFilter(get("/api/v1/notifications/./stream"))).isFalse();
  }

  @Test
  @DisplayName("a filtered response still gets its ETag, so the fix costs nothing elsewhere")
  void filteringStillProducesAnEtag() throws Exception {
    MockHttpServletRequest request = get("/api/v1/missions/search");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain =
        (req, res) -> {
          res.setContentType("application/json");
          res.getOutputStream()
              .write("{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        };

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader("ETag")).isNotBlank();
    assertThat(response.getContentAsString()).isEqualTo("{\"a\":1}");
  }

  @Test
  @DisplayName("a stream's bytes reach the response untouched")
  void streamBytesAreNotSwallowed() throws Exception {
    // The regression itself: with the plain filter and async started, this body never arrived.
    MockHttpServletRequest request = get("/api/v1/live-sync/stream");
    request.setAsyncSupported(true);
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new StreamingChain();

    filter.doFilter(request, response, chain);

    assertThat(response.getContentAsString()).contains("event:subscribed");
    assertThat(response.getHeader("ETag")).isNull();
  }

  /**
   * Builds a GET request for a path.
   *
   * @param path the request URI
   * @return the request
   */
  private static MockHttpServletRequest get(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    return request;
  }

  /** A chain that starts async and writes a frame, the way an {@code SseEmitter} does. */
  private static final class StreamingChain extends MockFilterChain {

    /** {@inheritDoc} */
    @Override
    public void doFilter(jakarta.servlet.ServletRequest request, ServletResponse response)
        throws IOException {
      request.startAsync();
      response.setContentType("text/event-stream");
      response
          .getOutputStream()
          .write("event:subscribed\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      response.flushBuffer();
    }
  }

  /** Guards the type this replaces, so a revert to the plain filter is visible in review. */
  @Test
  @DisplayName("it is still a ShallowEtagHeaderFilter, only a narrower one")
  void itRemainsTheSpringFilter() {
    assertThat(filter).isInstanceOf(ShallowEtagHeaderFilter.class);
  }
}
