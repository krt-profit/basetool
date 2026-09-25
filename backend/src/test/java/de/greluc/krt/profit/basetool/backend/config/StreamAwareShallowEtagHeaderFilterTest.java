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

import de.greluc.krt.profit.basetool.backend.filter.ApiCacheControlFilter;
import de.greluc.krt.profit.basetool.backend.filter.NoStoreApiScopes;
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
 *
 * <p>The second exemption is a cost fix rather than a correctness fix, and the cases below pin the
 * claim it rests on — that skipping the {@link NoStoreApiScopes} families changes no response
 * header, because Spring already refuses to put an ETag on a {@code no-store} response. That claim
 * is asserted against Spring's own filter ({@link #springItselfEmitsNoEtagOnANoStoreResponse()}),
 * not against a reading of its source.
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
        "/api/v1/live-sync/changed",
        "/api/v1/missions/search",
        "/api/v1/materials/matrix",
        "/api/v1/material-exchange/offers",
        "/api/v1/job-types",
        "/"
      })
  @DisplayName("everything else keeps its ETag, the large catalogues included")
  void ordinaryEndpointsAreStillFiltered(String path) {
    assertThat(filter.shouldNotFilter(get(path))).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/notifications",
        "/api/v1/notifications/unread-count",
        "/api/v1/bank/accounts",
        "/api/v1/org-units/bank/transactions",
        "/api/v1/users/me",
        "/api/v1/me/capabilities",
        "/api/v1/hangar/ships",
        "/api/v1/inventory/mission/00000000-0000-4000-8000-000000000000",
        "/api/v1/missions/00000000-0000-4000-8000-000000000000/finance-entries",
        "/api/v1/personal-inventory",
        "/api/v1/refinery-orders/all",
        "/api/v1/promotion/eligibility"
      })
  @DisplayName("a no-store family is not filtered either — its buffer could never pay for itself")
  void noStoreFamiliesBypassTheFilter(String path) {
    assertThat(filter.shouldNotFilter(get(path))).isTrue();
  }

  @Test
  @DisplayName("Spring itself emits no ETag on a no-store response, which is what makes this safe")
  void springItselfEmitsNoEtagOnANoStoreResponse() throws Exception {
    ShallowEtagHeaderFilter plain = new ShallowEtagHeaderFilter();
    MockHttpServletRequest request = get("/api/v1/users/me");
    MockHttpServletResponse response = new MockHttpServletResponse();

    plain.doFilter(request, response, noStoreJsonChain());

    assertThat(response.getHeader("ETag")).isNull();
    assertThat(response.getContentAsString()).isEqualTo("{\"a\":1}");
  }

  @Test
  @DisplayName("skipping a no-store family still delivers the body byte for byte")
  void aSkippedNoStoreFamilyStillDeliversItsBody() throws Exception {
    MockHttpServletRequest request = get("/api/v1/users/me");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, noStoreJsonChain());

    assertThat(response.getContentAsString()).isEqualTo("{\"a\":1}");
    assertThat(response.getHeader("ETag")).isNull();
    assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/notifications",
        "/api/v1/bank/accounts",
        "/api/v1/org-units/bank/transactions",
        "/api/v1/users/me",
        "/api/v1/me/capabilities",
        "/api/v1/finance-entries",
        "/api/v1/operations",
        "/api/v1/hangar/ships",
        "/api/v1/inventory",
        "/api/v1/personal-inventory",
        "/api/v1/personal-blueprints",
        "/api/v1/refinery-orders/all",
        "/api/v1/promotion/eligibility",
        "/api/v1/missions/00000000-0000-4000-8000-000000000000/finance-entries",
        "/api/v1/missions/search",
        "/api/v1/materials/matrix",
        "/api/v1/job-types"
      })
  @DisplayName("both filters answer from the same list, so neither can drift alone")
  void theTwoFiltersAgreeOnEveryFamily(String path) throws Exception {
    MockHttpServletRequest request = get(path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    new ApiCacheControlFilter().doFilter(request, response, new MockFilterChain());

    boolean noStore = "private, no-store".equals(response.getHeader("Cache-Control"));

    assertThat(filter.shouldNotFilter(get(path)))
        .as("ETag bypass must follow the no-store directive for %s", path)
        .isEqualTo(noStore);
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  @DisplayName("a write to a no-store family is still filtered, matching the directive's own scope")
  void writesToNoStoreFamiliesAreStillFiltered(String method) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/bank/accounts");
    request.setRequestURI("/api/v1/bank/accounts");

    assertThat(filter.shouldNotFilter(request)).isFalse();
  }

  @Test
  @DisplayName("a non-API path never pays the fourteen-pattern scan")
  void nonApiPathsAreNotScanned() {
    assertThat(filter.shouldNotFilter(get("/actuator/health"))).isFalse();
    assertThat(filter.shouldNotFilter(get("/v3/api-docs"))).isFalse();
  }

  @Test
  @DisplayName("the exempt list is the shared one, not a copy that can drift")
  void theExemptListIsTheSharedOne() {
    assertThat(NoStoreApiScopes.size())
        .as("the shared list must not be empty, or the agreement above is vacuous")
        .isGreaterThanOrEqualTo(14);
  }

  @Test
  @DisplayName("an unnormalised stream spelling is still NOT recognised, and that is bounded")
  void unnormalisedPathsAreNotRecognised() {
    assertThat(filter.shouldNotFilter(get("/api/v1/live-sync/./stream"))).isFalse();
  }

  @Test
  @DisplayName("the notification stream's unnormalised spelling is now covered, by the other list")
  void theNoStoreListNarrowsTheUnnormalisedHole() {
    assertThat(filter.shouldNotFilter(get("/api/v1/notifications/./stream"))).isTrue();
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

  /**
   * A chain answering like a {@code no-store} family: the directive first, then a JSON body.
   *
   * <p>The order matters and mirrors production. {@code ApiCacheControlFilter} runs at {@code
   * HIGHEST_PRECEDENCE + 20}, so the header is on the response before the body is written and
   * before this filter's write-back looks at it.
   *
   * @return a chain that writes {@code private, no-store} and a one-field body.
   */
  private static FilterChain noStoreJsonChain() {
    return (req, res) -> {
      ((jakarta.servlet.http.HttpServletResponse) res)
          .setHeader("Cache-Control", "private, no-store");
      res.setContentType("application/json");
      res.getOutputStream().write("{\"a\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    };
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
