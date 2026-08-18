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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Tests the trusted-proxy walk that decides which address every per-IP budget is charged to
 * (REQ-SEC-011).
 *
 * <p>The chain semantics are exercised against the static resolver so a case can be written in one
 * line; the request-attribute contract and the ordering are exercised through the filter itself,
 * because those are what the rest of the chain depends on.
 */
class ClientIpContextFilterTest {

  /** Our own proxy network: the hops that may speak about who the client is. */
  private static final List<IpAddressMatcher> TRUSTED =
      List.of(new IpAddressMatcher("10.0.0.0/24"), new IpAddressMatcher("172.28.0.0/16"));

  @Nested
  @DisplayName("the walk")
  class ResolveClientIpTests {

    @Test
    void untrustedPeer_ignoresTheHeaderEntirely() {
      // Reaching the container around the proxy must never let the caller name itself.
      assertEquals(
          "198.51.100.10",
          ClientIpContextFilter.resolveClientIp("198.51.100.10", "1.1.1.1", TRUSTED));
    }

    @Test
    void trustedPeerWithoutHeader_usesThePeer() {
      assertEquals("10.0.0.1", ClientIpContextFilter.resolveClientIp("10.0.0.1", null, TRUSTED));
      assertEquals("10.0.0.1", ClientIpContextFilter.resolveClientIp("10.0.0.1", "  ", TRUSTED));
    }

    @Test
    void singleHop_usesIt() {
      assertEquals(
          "203.0.113.7", ClientIpContextFilter.resolveClientIp("10.0.0.1", "203.0.113.7", TRUSTED));
    }

    @Test
    @DisplayName("a spoofed leading entry loses to the proxy-appended truth")
    void spoofedLeadingEntry_isNeverReached() {
      // nginx-proxy-manager appends with $proxy_add_x_forwarded_for, so everything left of the
      // last hop is client-supplied. This single assertion is the whole point of the change.
      assertEquals(
          "203.0.113.7",
          ClientIpContextFilter.resolveClientIp("10.0.0.1", "9.9.9.9, 203.0.113.7", TRUSTED));
    }

    @Test
    void ourOwnHopsAreSkipped() {
      assertEquals(
          "203.0.113.7",
          ClientIpContextFilter.resolveClientIp(
              "10.0.0.1", "9.9.9.9, 203.0.113.7, 10.0.0.9, 172.28.0.5", TRUSTED));
    }

    @Test
    void everyHopTrusted_fallsBackToThePeer() {
      // No client address in the chain at all; keying on one of our own hops would be a lie.
      assertEquals(
          "10.0.0.1",
          ClientIpContextFilter.resolveClientIp("10.0.0.1", "10.0.0.9, 172.28.0.5", TRUSTED));
    }

    @Test
    void emptyElementsAreSkippedRatherThanKeyedOn() {
      assertEquals(
          "203.0.113.7",
          ClientIpContextFilter.resolveClientIp("10.0.0.1", " , 203.0.113.7 ,", TRUSTED));
    }

    @Test
    @DisplayName("a non-IP token such as \"unknown\" is untrusted, not a crash")
    void nonIpTokenIsTreatedAsTheClient() {
      // Some proxies emit "unknown". IpAddressMatcher throws on it, so the walk must guard: the
      // token cannot be one of ours, therefore it terminates the walk like any untrusted hop.
      assertEquals(
          "unknown", ClientIpContextFilter.resolveClientIp("10.0.0.1", "unknown", TRUSTED));
    }

    @Test
    void ipv6ClientBehindOurProxy() {
      assertEquals(
          "2001:db8::1", ClientIpContextFilter.resolveClientIp("10.0.0.1", "2001:db8::1", TRUSTED));
    }

    @Test
    void noTrustedProxiesConfigured_disablesTheHeader() {
      assertEquals(
          "10.0.0.1", ClientIpContextFilter.resolveClientIp("10.0.0.1", "1.1.1.1", List.of()));
    }

    @Test
    void nullPeer_yieldsNull() {
      assertNull(ClientIpContextFilter.resolveClientIp(null, "1.1.1.1", TRUSTED));
    }
  }

  @Nested
  @DisplayName("the filter contract")
  class FilterTests {

    @Test
    void publishesTheResolvedAddressAndItsProvenance() throws Exception {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missions");
      request.setRemoteAddr("10.0.0.1");
      request.addHeader("X-Forwarded-For", "9.9.9.9, 203.0.113.7");

      filterWith(List.of("10.0.0.0/24"))
          .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

      assertEquals("203.0.113.7", request.getAttribute(ClientIpContextFilter.CLIENT_IP_ATTRIBUTE));
      assertTrue(
          (Boolean) request.getAttribute(ClientIpContextFilter.CLIENT_IP_FORWARDED_ATTRIBUTE),
          "a value taken from the chain must be reported as forwarded");
    }

    @Test
    void marksAPeerFallbackAsNotForwarded() throws Exception {
      // The key_source tag is the only signal that per-client bucketing has collapsed, so a
      // fallback must never be dressed up as a resolved client.
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missions");
      request.setRemoteAddr("198.51.100.10");
      request.addHeader("X-Forwarded-For", "1.1.1.1");

      filterWith(List.of("10.0.0.0/24"))
          .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

      assertEquals(
          "198.51.100.10", request.getAttribute(ClientIpContextFilter.CLIENT_IP_ATTRIBUTE));
      assertFalse(
          (Boolean) request.getAttribute(ClientIpContextFilter.CLIENT_IP_FORWARDED_ATTRIBUTE));
    }

    @Test
    void aRepeatedHeaderLineIsFoldedIntoOneChain() throws Exception {
      // Add-header proxies emit a second line instead of appending. getHeader() would return only
      // the client-supplied first one, and the walk would hand back exactly the spoof.
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missions");
      request.setRemoteAddr("10.0.0.1");
      request.addHeader("X-Forwarded-For", "9.9.9.9");
      request.addHeader("X-Forwarded-For", "203.0.113.7");

      filterWith(List.of("10.0.0.0/24"))
          .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

      assertEquals(
          "203.0.113.7",
          request.getAttribute(ClientIpContextFilter.CLIENT_IP_ATTRIBUTE),
          "both header lines form one chain; the rightmost untrusted hop wins");
    }

    @Test
    void aPeerlessRequestPublishesNoAttributeRatherThanRemovingIt() throws Exception {
      // setAttribute(name, null) is defined as removeAttribute, so publishing a null would leave
      // consumers unable to tell "did not run" from "ran and found nothing".
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missions");
      request.setRemoteAddr(null);

      filterWith(List.of("10.0.0.0/24"))
          .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

      assertNull(request.getAttribute(ClientIpContextFilter.CLIENT_IP_ATTRIBUTE));
      assertNull(request.getAttribute(ClientIpContextFilter.CLIENT_IP_FORWARDED_ATTRIBUTE));
    }

    @Test
    void wildcardEntryIsDroppedWithoutDroppingTheRestOfTheList() throws Exception {
      // "*" would restore blanket trust; the rest of a misconfigured list must still work.
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missions");
      request.setRemoteAddr("10.0.0.1");
      request.addHeader("X-Forwarded-For", "203.0.113.7");

      filterWith(List.of("*", "10.0.0.1"))
          .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

      assertEquals("203.0.113.7", request.getAttribute(ClientIpContextFilter.CLIENT_IP_ATTRIBUTE));
    }

    @Test
    void unparseableEntryIsDroppedRatherThanFailingStartup() throws Exception {
      MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missions");
      request.setRemoteAddr("10.0.0.1");
      request.addHeader("X-Forwarded-For", "203.0.113.7");

      filterWith(List.of("not-an-ip", "10.0.0.1"))
          .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

      assertEquals("203.0.113.7", request.getAttribute(ClientIpContextFilter.CLIENT_IP_ATTRIBUTE));
    }

    /**
     * Builds the filter with the given trusted-proxy allowlist.
     *
     * @param trustedProxies the raw configuration entries.
     * @return a filter whose allowlist is already compiled.
     */
    private ClientIpContextFilter filterWith(List<String> trustedProxies) {
      return new ClientIpContextFilter(trustedProxies);
    }
  }
}
