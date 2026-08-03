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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link ApiCacheControlFilter}: idempotent API GETs get revalidation headers, and
 * nothing else does.
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
    assertEquals("no-cache, must-revalidate", run("GET", "/api/v1/users").getHeader(CACHE_CONTROL));
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
        "no-cache, must-revalidate", run("GET", "/%61pi/v1/users").getHeader(CACHE_CONTROL));
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
