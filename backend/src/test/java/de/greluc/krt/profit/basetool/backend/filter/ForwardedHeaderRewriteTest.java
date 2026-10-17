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

import de.greluc.krt.profit.basetool.backend.platform.internal.ClientIpContextFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Pins the client-IP attribution of a chain without Spring's {@code ForwardedHeaderFilter}
 * (ADR-0272, REQ-SEC-011): the real TCP peer reaches the application unrewritten, the {@code
 * X-Forwarded-For} chain stays readable, and {@link ClientIpContextFilter} honours a forwarded
 * entry only when the peer is a trusted relay.
 *
 * <p>A spoofed leftmost entry must not move the attribution.
 */
class ForwardedHeaderRewriteTest {

  /** The chain an appending proxy produces: attacker-supplied entry first, real peer appended. */
  private static final String SPOOFED_THEN_REAL = "9.9.9.9, 203.0.113.7";

  /** The frontend network, the only trusted relay in this fixture. */
  private static final List<String> TRUSTED_FRONTEND = List.of("172.28.2.0/24");

  @Test
  @DisplayName("without the filter, the application still sees the real peer and the raw chain")
  void theRealPeerAndTheRawChainReachTheApplication() throws Exception {
    Observation observed = observe(new ClientIpContextFilter(List.of()), "172.28.2.5");

    assertEquals(
        "172.28.2.5",
        observed.remoteAddr(),
        "nothing rewrites the peer address any more, so a downstream component that reads it is"
            + " reading the actual TCP peer");
    assertEquals(
        SPOOFED_THEN_REAL,
        observed.forwardedFor(),
        "the header survives, which is what lets the resolution walk the chain itself");
  }

  @Test
  @DisplayName("a forwarded entry from a trusted relay is honoured")
  void aTrustedRelayMovesAttribution() throws Exception {
    Observation observed = observe(new ClientIpContextFilter(TRUSTED_FRONTEND), "172.28.2.5");

    assertEquals(
        "203.0.113.7",
        observed.resolvedClientIp(),
        "the frontend relays the member's browser address so the per-IP budget keys on the member"
            + " rather than on one container (REQ-SEC-032)");
  }

  @Test
  @DisplayName("a forwarded header from an untrusted peer does not move attribution")
  void anUntrustedPeerCannotSpeakForAnyone() throws Exception {
    Observation observed = observe(new ClientIpContextFilter(TRUSTED_FRONTEND), "172.28.9.9");

    assertEquals(
        "172.28.9.9",
        observed.resolvedClientIp(),
        "a peer that is on an internal network but is not a relay — a monitoring scrape, a future"
            + " sidecar — must not be able to claim to speak for an arbitrary member (ADR-0272)");
  }

  /**
   * Sends one representative proxied request through the client-IP resolution and captures what the
   * next filter in the chain observes.
   *
   * @param filter the resolution under test, with its trusted-proxy list
   * @param peer the TCP peer address to present
   * @return what the application sees
   * @throws Exception if the filter chain fails
   */
  private Observation observe(ClientIpContextFilter filter, String peer) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missions");
    request.setRemoteAddr(peer);
    request.addHeader("X-Forwarded-For", SPOOFED_THEN_REAL);
    request.addHeader("X-Forwarded-Proto", "https");

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
                    String.valueOf(req.getAttribute(ClientIpContextFilter.CLIENT_IP_ATTRIBUTE))));
          }
        };

    filter.doFilter(
        request, new MockHttpServletResponse(), new MockFilterChain(new HttpServlet() {}, probe));

    Observation observation = captured.get();
    assertNotNull(observation, "the probe filter must have run");
    return observation;
  }

  /**
   * What a filter placed after the client-IP resolution can see.
   *
   * @param remoteAddr the peer address as reported to the downstream filter
   * @param forwardedFor the {@code X-Forwarded-For} header as visible downstream
   * @param resolvedClientIp the address the resolution attributed the request to
   */
  private record Observation(String remoteAddr, String forwardedFor, String resolvedClientIp) {}
}
