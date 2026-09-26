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

package de.greluc.krt.profit.basetool.frontend.logging;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.config.ClientIpProperties;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Unit tests for {@link ClientIpContextFilter} (finding SEC-02): the originating client IP relayed
 * to the backend's per-IP rate limiter must be resolved trusted-proxy-aware so a client-supplied
 * {@code X-Forwarded-For} cannot change it. A spoofable attribution would let an unauthenticated
 * caller mint a fresh per-IP rate-limit bucket per request by rotating a forged header.
 */
class ClientIpContextFilterTest {

  /** The production-shaped trusted-proxy allowlist: the Docker bridge ranges NPM sits in. */
  private static final List<IpAddressMatcher> DOCKER_PROXIES =
      List.of(
          new IpAddressMatcher("172.17.0.0/16"),
          new IpAddressMatcher("172.18.0.0/16"),
          new IpAddressMatcher("172.19.0.0/16"));

  private static final String NPM_PEER = "172.18.0.5";
  private static final String REAL_CLIENT = "203.0.113.7";

  @AfterEach
  void cleanUp() {
    ClientIpContext.clear();
  }

  @Test
  void resolveClientIp_trustedPeerAppendedChain_returnsRealClientNotForgedLeftmost() {
    String resolved =
        ClientIpContextFilter.resolveClientIp(NPM_PEER, "1.2.3.4, " + REAL_CLIENT, DOCKER_PROXIES);

    assertThat(resolved).isEqualTo(REAL_CLIENT);
  }

  @Test
  void resolveClientIp_rotatingForgedLeftmost_yieldsSameClientIp() {
    String first =
        ClientIpContextFilter.resolveClientIp(NPM_PEER, "9.9.9.9, " + REAL_CLIENT, DOCKER_PROXIES);
    String second =
        ClientIpContextFilter.resolveClientIp(NPM_PEER, "8.8.8.8, " + REAL_CLIENT, DOCKER_PROXIES);

    assertThat(first).isEqualTo(REAL_CLIENT);
    assertThat(second).isEqualTo(REAL_CLIENT);
  }

  @Test
  void resolveClientIp_forgedEntryShapedLikeADockerIp_isStillIgnored() {
    String resolved =
        ClientIpContextFilter.resolveClientIp(
            NPM_PEER, "172.18.0.9, " + REAL_CLIENT, DOCKER_PROXIES);

    assertThat(resolved).isEqualTo(REAL_CLIENT);
  }

  @Test
  void resolveClientIp_npmReplacesHeaderWithSingleRealClient_returnsIt() {
    String resolved = ClientIpContextFilter.resolveClientIp(NPM_PEER, REAL_CLIENT, DOCKER_PROXIES);

    assertThat(resolved).isEqualTo(REAL_CLIENT);
  }

  @Test
  void resolveClientIp_untrustedPeerWithClientSuppliedHeader_ignoresHeaderUsesPeer() {
    String attackerPeer = "203.0.113.50";
    String resolved =
        ClientIpContextFilter.resolveClientIp(attackerPeer, "10.0.0.1, 1.2.3.4", DOCKER_PROXIES);

    assertThat(resolved).isEqualTo(attackerPeer);
  }

  @Test
  void resolveClientIp_trustedPeerNoHeader_fallsBackToPeer() {
    String resolved = ClientIpContextFilter.resolveClientIp(NPM_PEER, null, DOCKER_PROXIES);

    assertThat(resolved).isEqualTo(NPM_PEER);
  }

  @Test
  void resolveClientIp_emptyTrustedProxies_devProfile_ignoresHeader() {
    String resolved = ClientIpContextFilter.resolveClientIp("127.0.0.1", "1.2.3.4", List.of());

    assertThat(resolved).isEqualTo("127.0.0.1");
  }

  @Test
  void resolveClientIp_multipleTrustedHops_skipsThemAndReturnsFirstUntrustedFromRight() {
    String resolved =
        ClientIpContextFilter.resolveClientIp(
            NPM_PEER, REAL_CLIENT + ", 172.19.0.4, 172.18.0.5", DOCKER_PROXIES);

    assertThat(resolved).isEqualTo(REAL_CLIENT);
  }

  @Test
  void resolveClientIp_nonIpTokenInChain_treatedAsUntrusted() {
    String resolved =
        ClientIpContextFilter.resolveClientIp(NPM_PEER, "unknown, " + REAL_CLIENT, DOCKER_PROXIES);

    assertThat(resolved).isEqualTo(REAL_CLIENT);
  }

  @Test
  void resolveClientIp_nullRemoteAddr_returnsNull() {
    assertThat(ClientIpContextFilter.resolveClientIp(null, "1.2.3.4", DOCKER_PROXIES)).isNull();
  }

  @Test
  void doFilterInternal_bindsResolvedClientIpForTheChainAndClearsAfter() throws Exception {
    ClientIpProperties props = new ClientIpProperties(List.of("172.18.0.0/16"));
    ClientIpContextFilter filter = new ClientIpContextFilter(props);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(NPM_PEER);
    request.addHeader("X-Forwarded-For", "1.2.3.4, " + REAL_CLIENT);
    MockHttpServletResponse response = new MockHttpServletResponse();

    String[] boundDuringChain = new String[1];
    filter.doFilter(request, response, (req, res) -> boundDuringChain[0] = ClientIpContext.get());

    assertThat(boundDuringChain[0])
        .as("the forged leftmost entry is ignored; the real client is bound for the relay")
        .isEqualTo(REAL_CLIENT);
    assertThat(ClientIpContext.get()).as("cleared after the chain").isNull();
  }
}
