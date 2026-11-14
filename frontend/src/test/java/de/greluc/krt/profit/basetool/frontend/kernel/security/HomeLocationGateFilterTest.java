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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.HomeLocationStatusDto;
import jakarta.servlet.FilterChain;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Behaviour of the frontend's home-location gate (REQ-PATCH-020): a pending member is sent to the
 * home-location page on every kind of request, the gate pages stay open, and only a verdict that
 * lets the member through is cached.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HomeLocationGateFilterTest {

  private static final String STATUS = "/api/v1/patch-changes/me/home-location/status";

  @Mock private BackendApiClient backendApiClient;
  @Mock private FilterChain filterChain;

  private HomeLocationGateFilter filter;
  private MockHttpSession session;

  @BeforeEach
  void setUp() {
    filter = new HomeLocationGateFilter(backendApiClient, new MockEnvironment());
    session = new MockHttpSession();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "member", "n/a", AuthorityUtils.createAuthorityList("ROLE_KRT_MEMBER")));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void redirectsAPendingMemberToTheHomeLocationPage() throws Exception {
    stubRequired(true);

    MockHttpServletResponse response = invoke(request("GET", "/missions"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/home-location");
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  void signalsTheGateToAnAjaxCaller() throws Exception {
    stubRequired(true);
    MockHttpServletRequest request = request("POST", "/missions/x/ajax");
    request.addHeader("X-Requested-With", "XMLHttpRequest");

    MockHttpServletResponse response = invoke(request);

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader("X-Home-Location-Required")).isEqualTo("/home-location");
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  void handsAnSseStreamOffWithItsOwnEvent() throws Exception {
    stubRequired(true);
    MockHttpServletRequest request = request("GET", "/notifications/stream");
    request.addHeader("Accept", "text/event-stream");

    MockHttpServletResponse response = invoke(request);

    assertThat(response.getContentAsString())
        .isEqualTo("event: home-location-gate\ndata: /home-location\n\n");
    verify(filterChain, never()).doFilter(any(), any());
  }

  @Test
  void marksAWebSocketHandshakeForATerminalClose() throws Exception {
    stubRequired(true);
    MockHttpServletRequest request = request("GET", "/ws/sync");
    request.addHeader("Upgrade", "websocket");

    invoke(request);

    assertThat(SessionGateHandoff.gateUrl(request)).isEqualTo("/home-location");
    verify(filterChain).doFilter(any(), any());
  }

  @Test
  void leavesTheGatePagesOpen() throws Exception {
    stubRequired(true);

    invoke(request("GET", "/home-location"));
    invoke(request("GET", "/terms/accept"));

    verify(filterChain, times(2)).doFilter(any(), any());
  }

  @Test
  void cachesOnlyAVerdictThatLetsTheMemberThrough() throws Exception {
    stubRequired(false);
    invoke(request("GET", "/missions"));
    invoke(request("GET", "/missions"));
    verify(backendApiClient, times(1)).get(eq(STATUS), eq(HomeLocationStatusDto.class));

    HomeLocationGateFilter.clearCachedVerdict(request("GET", "/missions"));
    stubRequired(true);
    invoke(request("GET", "/missions"));
    MockHttpServletResponse held = invoke(request("GET", "/missions"));

    assertThat(held.getRedirectedUrl()).isEqualTo("/home-location");
    verify(backendApiClient, times(3)).get(eq(STATUS), eq(HomeLocationStatusDto.class));
  }

  /**
   * Stubs the backend's verdict.
   *
   * @param required whether the member must choose
   */
  private void stubRequired(boolean required) {
    when(backendApiClient.get(STATUS, HomeLocationStatusDto.class))
        .thenReturn(new HomeLocationStatusDto(required));
  }

  /**
   * Builds a request in the test's session.
   *
   * @param method the HTTP method
   * @param path the context-relative path
   * @return the request
   */
  private @NotNull MockHttpServletRequest request(@NotNull String method, @NotNull String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setRequestURI(path);
    request.setSession(session);
    return request;
  }

  /**
   * Runs the filter.
   *
   * @param request the request
   * @return the response
   * @throws Exception if the filter throws
   */
  private @NotNull MockHttpServletResponse invoke(@NotNull MockHttpServletRequest request)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, filterChain);
    return response;
  }
}
