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

package de.greluc.krt.profit.basetool.backend.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.Filter;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.CorsFilter;

/**
 * The precondition that makes CSRF and CORS unnecessary on the main chain rather than merely
 * exempted (ADR-0271, REQ-SEC-044): the chain accepts <strong>nothing but a bearer JWT</strong> and
 * creates no session.
 *
 * <p>ADR-0144 stated that precondition in prose and pinned the exemption list instead, so nothing
 * checked it. This fails on the cause — an ambient credential a browser could be made to attach —
 * rather than on a path somebody forgot to add. The Prometheus scrape chain is HTTP basic by design
 * (ADR-0265) and is asserted separately, so a future change cannot satisfy this test by relaxing
 * that one.
 */
@SpringBootTest
class SecurityChainBearerOnlyTest {

  /**
   * Credential filters a browser can be made to attach by itself. A chain holding one of these is a
   * chain CSRF would have to protect.
   */
  private static final List<Class<? extends Filter>> AMBIENT_CREDENTIAL_FILTERS =
      List.of(
          UsernamePasswordAuthenticationFilter.class,
          BasicAuthenticationFilter.class,
          RememberMeAuthenticationFilter.class);

  @Autowired private FilterChainProxy springSecurityFilterChain;

  @Autowired private WebApplicationContext context;

  /**
   * The chain that serves the API, chosen the way {@link FilterChainProxy} chooses it.
   *
   * @param path the request path whose chain is wanted
   * @return the matching chain
   */
  private @NotNull SecurityFilterChain chainFor(@NotNull String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), path);
    request.setRequestURI(path);
    return springSecurityFilterChain.getFilterChains().stream()
        .filter(candidate -> candidate.matches(request))
        .findFirst()
        .orElseThrow();
  }

  /**
   * The filter classes of one chain.
   *
   * @param path the request path whose chain is wanted
   * @return the chain's filter classes, in execution order
   */
  private @NotNull List<Class<?>> filterTypes(@NotNull String path) {
    return chainFor(path).getFilters().stream().<Class<?>>map(Filter::getClass).toList();
  }

  @Test
  @DisplayName("the main chain holds a bearer-token filter and no ambient-credential filter")
  void theMainChainAcceptsOnlyABearerToken() {
    List<Class<?>> filters = filterTypes("/api/v1/missions");

    assertThat(filters)
        .as("the API is authenticated by a bearer JWT, so its filter must be on the chain")
        .anyMatch(type -> type.getSimpleName().equals("BearerTokenAuthenticationFilter"));

    for (Class<? extends Filter> ambient : AMBIENT_CREDENTIAL_FILTERS) {
      assertThat(filters)
          .as(
              "%s puts an ambient credential on the main chain — the exact condition CSRF exists"
                  + " for. Adding one means re-deciding ADR-0271, not editing this test",
              ambient.getSimpleName())
          .noneMatch(ambient::isAssignableFrom);
    }
    assertThat(filters)
        .as("no OAuth2 or SAML login flow belongs on a resource server's chain")
        .noneMatch(type -> type.getSimpleName().startsWith("OAuth2Login"))
        .noneMatch(type -> type.getSimpleName().startsWith("OAuth2AuthorizationRequest"))
        .noneMatch(type -> type.getSimpleName().startsWith("Saml2"));
  }

  @Test
  @DisplayName("CSRF and CORS are off on the main chain, not merely exempted")
  void csrfAndCorsAreOffOnTheMainChain() {
    List<Class<?>> filters = filterTypes("/api/v1/missions");

    assertThat(filters)
        .as("CSRF is disabled unconditionally, so the filter is absent in every profile")
        .noneMatch(CsrfFilter.class::isAssignableFrom);
    assertThat(filters)
        .as("CORS is disabled, so there is no preflight to answer and no property to answer one")
        .noneMatch(CorsFilter.class::isAssignableFrom);
  }

  @Test
  @DisplayName("an authenticated API request creates no HTTP session")
  void anAuthenticatedRequestCreatesNoSession() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

    MvcResult result = mockMvc.perform(get("/api/v1/users/me").with(jwt())).andReturn();

    assertThat(result.getRequest().getSession(false))
        .as(
            "SessionCreationPolicy.STATELESS is what makes a bearer-only chain safe without CSRF;"
                + " a session here would be an ambient credential by another name")
        .isNull();
  }

  @Test
  @DisplayName("the Prometheus scrape chain keeps Spring's CSRF default, so this guard is scoped")
  void theScrapeChainIsUntouched() {
    assertThat(filterTypes("/actuator/prometheus"))
        .as(
            "the scrape chain is HTTP basic against the scrape identity (ADR-0265) — precisely the"
                + " credential a browser can be made to attach — so nothing is relaxed there and"
                + " it keeps Spring's CSRF default; it is deliberately outside this guard")
        .anyMatch(CsrfFilter.class::isAssignableFrom);
    assertThat(chainFor("/actuator/prometheus"))
        .as("the scrape chain must not be the chain this guard measures")
        .isNotSameAs(chainFor("/api/v1/missions"));
  }
}
