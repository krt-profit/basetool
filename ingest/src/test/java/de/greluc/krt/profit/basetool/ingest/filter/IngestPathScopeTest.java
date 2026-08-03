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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for {@link IngestPathScope}, the single scope decision the client-identity, payload,
 * rate-limit and access-log filters share.
 *
 * <p>The point of the class — and of these tests — is that the decision is made on the
 * <em>decoded</em> path, the same one {@code RequestMappingHandlerMapping} routes on. The raw
 * {@code getRequestURI().startsWith("/v1/")} test it replaced said "not an ingest path" for an
 * encoded spelling the dispatcher happily decoded and delivered, which silently switched off all
 * four filters at once.
 */
class IngestPathScopeTest {

  /**
   * Builds a request whose raw URI is exactly the given string.
   *
   * @param uri the raw, possibly percent-encoded request URI
   * @return a mock POST request carrying that URI
   */
  private static MockHttpServletRequest request(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
    request.setRequestURI(uri);
    return request;
  }

  @Test
  void matchesAPlainIngestPath() {
    assertThat(IngestPathScope.isIngestRequest(request("/v1/refinery-extract"))).isTrue();
  }

  @Test
  void matchesAPercentEncodedIngestPath() {
    // %76 = 'v'. Spring MVC decodes this back to /v1/... and dispatches it, so the protective
    // filters must see it as in scope.
    assertThat(IngestPathScope.isIngestRequest(request("/%761/refinery-extract"))).isTrue();
    assertThat(IngestPathScope.isIngestRequest(request("/v%31/refinery-extract"))).isTrue();
  }

  @Test
  void doesNotMatchTheUnauthenticatedOperationalEndpoints() {
    // Gating these would break the container healthcheck and the Prometheus scrape.
    assertThat(IngestPathScope.isIngestRequest(request("/actuator/health"))).isFalse();
    assertThat(IngestPathScope.isIngestRequest(request("/v3/api-docs"))).isFalse();
  }

  @Test
  void doesNotMatchAPathThatMerelyStartsWithTheScopeLiteral() {
    // /v1x is a different first segment, not a sub-path of /v1 — the segment-wise match keeps them
    // apart where a naive string prefix would not.
    assertThat(IngestPathScope.isIngestRequest(request("/v1x/refinery-extract"))).isFalse();
  }
}
