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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Pins the {@code ForwardedHeaderFilter} behaviour that {@link ClientIpContextFilter} and {@code
 * ForwardedHeaderConfig} exist to work around (REQ-SEC-011).
 *
 * <p>This is a test of the framework, not of our code, and that is the point. The decision to set
 * {@code server.forward-headers-strategy: none} and re-register the filter one slot later is
 * expensive to justify from prose alone, and a future reader is likely to try reverting it. What
 * makes the detour necessary is exactly what is asserted here: after that filter runs, a downstream
 * filter sees a peer address the client chose and no chain to re-derive it from. If a Spring
 * upgrade ever changes this, that reader deserves a failing test rather than a silent regression in
 * rate-limit attribution.
 */
class ForwardedHeaderRewriteTest {

  /** The chain an appending proxy produces: attacker-supplied entry first, real peer appended. */
  private static final String SPOOFED_THEN_REAL = "9.9.9.9, 203.0.113.7";

  @Test
  @DisplayName("after ForwardedHeaderFilter, getRemoteAddr() is the leftmost - client-chosen - hop")
  void rewritesRemoteAddrToTheLeftmostHop() throws Exception {
    Observation observed = observeThroughForwardedHeaderFilter();

    assertEquals(
        "9.9.9.9",
        observed.remoteAddr(),
        "the real peer 172.28.0.5 is replaced by the first entry of the chain, which any client "
            + "can set — this is why attribution cannot happen downstream of this filter");
  }

  @Test
  @DisplayName("after ForwardedHeaderFilter, the X-Forwarded-For chain is no longer readable")
  void hidesTheForwardedForHeader() throws Exception {
    Observation observed = observeThroughForwardedHeaderFilter();

    assertNull(
        observed.forwardedFor(),
        "the header is stripped from the wrapped request, so a downstream filter cannot walk the "
            + "chain itself to recover the proxy-appended client address");
  }

  @Test
  @DisplayName("the scheme and host rewriting we still want is unaffected by the reordering")
  void stillRebuildsSchemeAndHost() throws Exception {
    Observation observed = observeThroughForwardedHeaderFilter();

    assertEquals("api.profit-base.online", observed.serverName(), "host must come from the header");
    assertTrue(observed.secure(), "X-Forwarded-Proto: https must mark the request secure");
  }

  /**
   * Sends one representative proxied request through a bare {@link ForwardedHeaderFilter} and
   * captures what the next filter in the chain observes.
   *
   * @return the four values the design decision turns on.
   * @throws Exception if the filter chain fails.
   */
  private Observation observeThroughForwardedHeaderFilter() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missions");
    request.setRemoteAddr("172.28.0.5");
    request.addHeader("X-Forwarded-For", SPOOFED_THEN_REAL);
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-Host", "api.profit-base.online");

    AtomicReference<Observation> captured = new AtomicReference<>();
    OncePerRequestFilter probe =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
            captured.set(
                new Observation(
                    req.getRemoteAddr(),
                    req.getHeader("X-Forwarded-For"),
                    req.getServerName(),
                    req.isSecure()));
          }
        };

    new ForwardedHeaderFilter()
        .doFilter(
            request,
            new MockHttpServletResponse(),
            new MockFilterChain(new HttpServlet() {}, probe));

    Observation observation = captured.get();
    assertNotNull(observation, "the probe filter must have run");
    return observation;
  }

  /**
   * What a filter placed after {@link ForwardedHeaderFilter} can see.
   *
   * @param remoteAddr the peer address as reported to the downstream filter.
   * @param forwardedFor the {@code X-Forwarded-For} header as visible downstream, or {@code null}.
   * @param serverName the host name after rewriting.
   * @param secure whether the request is reported as TLS-terminated.
   */
  private record Observation(
      String remoteAddr, String forwardedFor, String serverName, boolean secure) {}
}
