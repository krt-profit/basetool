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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.RequestBodyLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link RequestBodySizeLimitFilter}: an oversized non-multipart body on a capped
 * path is refused with 413 before it reaches the controller (via both the declared {@code
 * Content-Length} fast path and the chunked stream-counting path), while a within-cap body, a
 * non-capped path, a multipart upload and the disabled state all pass through untouched.
 */
class RequestBodySizeLimitFilterTest {

  private static final String CAPPED_PATH = "/api/v1/refinery-orders/import-extract";

  private RequestBodyLimitProperties properties;
  private SimpleMeterRegistry meterRegistry;
  private RequestBodySizeLimitFilter filter;

  @BeforeEach
  void setUp() {
    properties = new RequestBodyLimitProperties();
    properties.setEnabled(true);
    properties.setMaxBytes(100); // tiny cap so tests hit it with small bodies
    properties.setPaths(List.of(CAPPED_PATH));

    AppProblemProperties problemProperties = new AppProblemProperties();
    problemProperties.setBaseUri("https://profit-base.online/problems/");

    meterRegistry = new SimpleMeterRegistry();
    filter = new RequestBodySizeLimitFilter(properties, problemProperties, meterRegistry);
  }

  @Test
  void declaredOversizedBody_onCappedPath_isRejected413() throws Exception {
    MockHttpServletRequest req = jsonRequest(CAPPED_PATH, "x".repeat(200));
    MockHttpServletResponse resp = new MockHttpServletResponse();
    TrackingChain chain = new TrackingChain();

    filter.doFilter(req, resp, chain);

    assertEquals(413, resp.getStatus());
    assertEquals("application/problem+json", resp.getContentType());
    assertTrue(resp.getContentAsString().contains("\"code\":\"REQUEST_BODY_TOO_LARGE\""));
    assertFalse(chain.called, "an oversized body must not reach the controller");
    assertEquals(1.0d, meterRegistry.counter(MetricNames.REQUEST_BODY_REJECTED).count());
  }

  @Test
  void withinCapBody_onCappedPath_passesThrough() throws Exception {
    MockHttpServletRequest req = jsonRequest(CAPPED_PATH, "{\"ok\":true}");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    TrackingChain chain = new TrackingChain();

    filter.doFilter(req, resp, chain);

    assertTrue(chain.called, "a within-cap body must reach the controller");
    assertEquals(0.0d, meterRegistry.counter(MetricNames.REQUEST_BODY_REJECTED).count());
  }

  @Test
  void oversizedBody_onNonCappedPath_passesThrough() throws Exception {
    MockHttpServletRequest req = jsonRequest("/api/v1/missions", "x".repeat(500));
    MockHttpServletResponse resp = new MockHttpServletResponse();
    TrackingChain chain = new TrackingChain();

    filter.doFilter(req, resp, chain);

    assertTrue(chain.called, "the cap applies only to the configured paths");
  }

  /**
   * The cap is not sheddable by percent-encoding the capped path.
   *
   * <p>{@code getRequestURI()} is the raw, still-encoded URI while Spring MVC routes on the decoded
   * path, so the exact {@code paths.contains(uri)} test this replaced left the cap off for {@code
   * /%61pi/v1/refinery-orders/import-extract} — which {@code RequestMappingHandlerMapping} then
   * decoded and delivered to the very controller the cap protects, unbounded. The default {@code
   * StrictHttpFirewall} blocks {@code %2e}/{@code %2f}/{@code %25} but not {@code %61}. Must be a
   * direct filter test: MockMvc normalises the path before the filter runs.
   */
  @Test
  void declaredOversizedBody_onPercentEncodedCappedPath_isRejected413() throws Exception {
    MockHttpServletRequest req =
        jsonRequest("/%61pi/v1/refinery-orders/import-extract", "x".repeat(200));
    MockHttpServletResponse resp = new MockHttpServletResponse();
    TrackingChain chain = new TrackingChain();

    filter.doFilter(req, resp, chain);

    assertEquals(413, resp.getStatus());
    assertFalse(chain.called, "an encoded spelling of a capped path must not shed the cap");
  }

  /**
   * The capped set stays exact: a path that merely starts with a configured one is not capped, so
   * replacing the string comparison with a literal pattern did not widen the scope.
   */
  @Test
  void oversizedBody_onPathMerelyPrefixedWithACappedPath_passesThrough() throws Exception {
    MockHttpServletRequest req = jsonRequest(CAPPED_PATH + "-preview", "x".repeat(500));
    MockHttpServletResponse resp = new MockHttpServletResponse();
    TrackingChain chain = new TrackingChain();

    filter.doFilter(req, resp, chain);

    assertTrue(chain.called, "only the configured paths are capped");
  }

  @Test
  void multipartUpload_onCappedPath_isSkipped() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", CAPPED_PATH);
    req.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE + "; boundary=abc");
    req.setContent("x".repeat(500).getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse resp = new MockHttpServletResponse();
    TrackingChain chain = new TrackingChain();

    filter.doFilter(req, resp, chain);

    assertTrue(chain.called, "multipart uploads are bounded by the multipart cap, not this filter");
  }

  @Test
  void disabled_passesEverythingThrough() throws Exception {
    properties.setEnabled(false);
    MockHttpServletRequest req = jsonRequest(CAPPED_PATH, "x".repeat(500));
    MockHttpServletResponse resp = new MockHttpServletResponse();
    TrackingChain chain = new TrackingChain();

    filter.doFilter(req, resp, chain);

    assertTrue(chain.called);
  }

  @Test
  void chunkedOversizedBody_withoutContentLength_isRejected413() throws Exception {
    // A chunked request declares no Content-Length (getContentLengthLong() == -1); the filter must
    // count the stream and reject once it crosses the cap rather than trust the missing length.
    MockHttpServletRequest req =
        new MockHttpServletRequest("POST", CAPPED_PATH) {
          @Override
          public long getContentLengthLong() {
            return -1;
          }

          @Override
          public int getContentLength() {
            return -1;
          }
        };
    req.setContentType(MediaType.APPLICATION_JSON_VALUE);
    req.setContent("x".repeat(200).getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse resp = new MockHttpServletResponse();
    TrackingChain chain = new TrackingChain();

    filter.doFilter(req, resp, chain);

    assertEquals(413, resp.getStatus());
    assertFalse(chain.called);
  }

  private static MockHttpServletRequest jsonRequest(String path, String body) {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
    req.setContentType(MediaType.APPLICATION_JSON_VALUE);
    req.setContent(body.getBytes(StandardCharsets.UTF_8));
    return req;
  }

  private static final class TrackingChain implements FilterChain {
    private boolean called;

    @Override
    public void doFilter(
        jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
        throws IOException, ServletException {
      called = true;
    }
  }
}
