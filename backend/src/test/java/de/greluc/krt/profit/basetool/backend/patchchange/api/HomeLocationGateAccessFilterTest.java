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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.kernel.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

/**
 * Behaviour of the backend's home-location gate (REQ-PATCH-020): a pending member's web calls are
 * refused, the gate's own endpoints and the terms endpoints stay open, and an exchange client's
 * relayed call passes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HomeLocationGateAccessFilterTest {

  private static final UUID USER_ID = UUID.randomUUID();

  @Mock private HomeLocationGateCheck gateCheck;
  @Mock private FilterChain filterChain;

  private MeterRegistry meterRegistry;
  private HomeLocationGateAccessFilter filter;

  @BeforeEach
  void setUp() {
    StaticMessageSource messages = new StaticMessageSource();
    messages.addMessage("problem.home_location_required.title", Locale.ENGLISH, "Choose");
    messages.addMessage("problem.home_location_required.detail", Locale.ENGLISH, "Choose.");
    meterRegistry = new SimpleMeterRegistry();
    filter =
        new HomeLocationGateAccessFilter(
            gateCheck,
            messages,
            new ProblemResponseFactory(BoundProperties.defaults(AppProblemProperties.class)),
            new ObjectMapper(),
            meterRegistry);
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(USER_ID.toString()).build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void letsAMemberWithNothingToChooseThrough() throws Exception {
    when(gateCheck.mustChoose(USER_ID)).thenReturn(false);

    assertThat(invoke("/api/v1/missions").getStatus()).isEqualTo(200);
    verify(filterChain).doFilter(any(), any());
  }

  @Test
  void refusesAPendingMemberWithTheStableCode() throws Exception {
    when(gateCheck.mustChoose(USER_ID)).thenReturn(true);

    MockHttpServletResponse response = invoke("/api/v1/missions");

    verify(filterChain, never()).doFilter(any(), any());
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("HOME_LOCATION_REQUIRED");
    assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    assertThat(
            meterRegistry.counter("basetool.http.error", "code", "HOME_LOCATION_REQUIRED").count())
        .isEqualTo(1.0);
  }

  @Test
  void leavesTheGatesOwnEndpointsAndTheTermsEndpointsOpen() throws Exception {
    when(gateCheck.mustChoose(USER_ID)).thenReturn(true);

    assertThat(invoke(HomeLocationGateAccessFilter.PROMPT_PATH).getStatus()).isEqualTo(200);
    assertThat(invoke(HomeLocationGateAccessFilter.STATUS_PATH).getStatus()).isEqualTo(200);
    assertThat(invoke("/api/v1/terms/status").getStatus()).isEqualTo(200);
    assertThat(invoke("/api/v1/users/me").getStatus()).isEqualTo(200);
  }

  @Test
  void letsARelayedExchangeCallThrough() throws Exception {
    when(gateCheck.mustChoose(USER_ID)).thenReturn(true);
    SecurityContextHolder.getContext().setAuthentication(new Relayed());

    assertThat(invoke("/api/v1/exchange/me/stock").getStatus()).isEqualTo(200);
    verify(gateCheck, never()).mustChoose(any());
  }

  @Test
  void leavesEverythingOutsideTheApiAlone() throws Exception {
    when(gateCheck.mustChoose(USER_ID)).thenReturn(true);

    assertThat(invoke("/actuator/health").getStatus()).isEqualTo(200);
    verify(gateCheck, never()).mustChoose(any());
  }

  /**
   * Runs the filter against the given path.
   *
   * @param path the context-relative request path
   * @return the response the filter produced
   * @throws Exception if the filter throws
   */
  private @NotNull MockHttpServletResponse invoke(@NotNull String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setRequestURI(path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, filterChain);
    return response;
  }

  /** An exchange client's relayed request on the member's behalf. */
  private static final class Relayed extends AbstractAuthenticationToken
      implements SubjectAuthentication {

    /** Relays for the test member. */
    private Relayed() {
      super(List.of());
      setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public Object getPrincipal() {
      return USER_ID.toString();
    }

    @Override
    public @NotNull String subject() {
      return USER_ID.toString();
    }

    @Override
    public String externalClient() {
      return "versekit";
    }
  }
}
