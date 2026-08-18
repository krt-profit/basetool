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

package de.greluc.krt.profit.basetool.backend.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link ApiCacheControlFilter}: idempotent API GETs get revalidation headers, the
 * sensitive families get {@code no-store} instead, and nothing else is touched.
 */
class ApiCacheControlFilterTest {

  private static final String CACHE_CONTROL = "Cache-Control";

  private final ApiCacheControlFilter filter = new ApiCacheControlFilter();

  /**
   * Runs the filter over one request.
   *
   * @param method the HTTP method
   * @param uri the raw request URI
   * @return the response after the filter ran
   */
  private MockHttpServletResponse run(String method, String uri) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setRequestURI(uri);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void apiGet_getsRevalidationHeaders() throws Exception {
    // An ordinary family: storable by an intermediary as long as it revalidates first.
    assertEquals(
        "no-cache, must-revalidate", run("GET", "/api/v1/missions").getHeader(CACHE_CONTROL));
  }

  /**
   * A percent-encoded API path still gets the headers.
   *
   * <p>{@code getRequestURI()} is raw while Spring MVC routes on the decoded path, so the raw
   * {@code startsWith("/api/")} test this replaced let {@code /%61pi/v1/users} reach the API
   * handler with no revalidation headers at all — the one response class that must never be served
   * stale from an intermediary. Must be a direct filter test: MockMvc normalises the path first.
   */
  @Test
  void percentEncodedApiGet_stillGetsRevalidationHeaders() throws Exception {
    assertEquals(
        "no-cache, must-revalidate", run("GET", "/%61pi/v1/missions").getHeader(CACHE_CONTROL));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/bank/accounts",
        "/api/v1/users/42",
        "/api/v1/me",
        "/api/v1/notifications",
        "/api/v1/notifications/stream"
      })
  void sensitiveFamilies_areNeverStored(String uri) throws Exception {
    // Balances, member records and one person's feed must not sit in a proxy or a disk cache at
    // all — revalidation is not enough, because it still permits the copy to exist.
    assertEquals("private, no-store", run("GET", uri).getHeader(CACHE_CONTROL));
  }

  @Test
  void percentEncodedSensitivePath_doesNotEscapeIntoTheWeakerDirective() throws Exception {
    // Same trap as the /api scope itself (REQ-SEC-029): the match runs on the decoded path, so an
    // encoded spelling cannot downgrade a bank response to merely revalidatable.
    assertEquals(
        "private, no-store", run("GET", "/api/v1/%62ank/accounts").getHeader(CACHE_CONTROL));
  }

  @Test
  void sensitiveFamiliesStillCarryTheVaryHeader() throws Exception {
    assertEquals("Accept-Encoding", run("GET", "/api/v1/bank/accounts").getHeader("Vary"));
  }

  @Test
  void nonApiGet_isUntouched() throws Exception {
    assertNull(run("GET", "/actuator/health").getHeader(CACHE_CONTROL));
  }

  @Test
  void apiWrite_isUntouched() throws Exception {
    // Only idempotent GETs are revalidatable; a POST response carries no cache semantics here.
    assertNull(run("POST", "/api/v1/users").getHeader(CACHE_CONTROL));
  }
}
