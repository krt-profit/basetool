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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Pins which paths cookie-based CSRF must not refuse.
 *
 * <p>This test exists because nothing else in the suite can see the rule at all. The {@code test}
 * profile disables CSRF outright so MockMvc can post without first fetching a token, so every
 * {@code @SpringBootTest} here runs the branch that has no CSRF — and the production branch, the
 * one that shipped a defect, is exercised by no test in the repository.
 *
 * <p>What shipped: {@code ignoringRequestMatchers} named five paths, so every other write under
 * {@code /api/v1} answered {@code 403 MissingCsrfToken} to a caller with no CSRF cookie. Every
 * bearer client is such a caller, which is the entire native app. Booking stock out of the Lager,
 * taking an Auftrag and moving its status, and setting a bank account's balance target were all
 * refused in production, while the two families that happened to be on the list worked. The nightly
 * {@code edge-deny-probe} had been red on exactly those four paths for two days: it asserts {@code
 * 401} for an anonymous write, and the CSRF filter runs ahead of authorization and answered {@code
 * 403} first.
 *
 * <p>The chain is stateless and bearer-only, so there is no ambient credential a cross-site request
 * could ride and the check could never have protected anything on this surface.
 */
@DisplayName("CSRF exemptions")
class SecurityConfigCsrfExemptionTest {

  /** The exemption list, as the filter chain will evaluate it. */
  private static final RequestMatcher EXEMPT =
      new OrRequestMatcher(
          Arrays.stream(SecurityConfig.CSRF_EXEMPT_PATHS)
              .map(p -> (RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(p))
              .toList());

  /**
   * Builds a request the way the CSRF filter sees one.
   *
   * @param method the HTTP verb; only unsafe verbs are ever tested here.
   * @param uri the request URI.
   * @return the request.
   */
  private static MockHttpServletRequest write(String method, String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setRequestURI(uri);
    return request;
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        // The four the edge-deny probe caught, verbatim.
        "/api/v1/inventory/00000000-0000-4000-8000-00000000cafe/book-out",
        "/api/v1/orders/00000000-0000-4000-8000-00000000cafe/assignees/00000000-0000-4000-8000-00000000cafe",
        "/api/v1/orders/00000000-0000-4000-8000-00000000cafe/status",
        "/api/v1/org-units/bank/accounts/00000000-0000-4000-8000-00000000cafe/balance-target",
        // The two families that were on the old list and therefore worked. They must keep working.
        "/api/v1/missions/00000000-0000-4000-8000-00000000cafe/join",
        "/api/v1/operations/00000000-0000-4000-8000-00000000cafe/payouts/paid-out",
        // Machine-to-machine, its own shared-secret header (REQ-SEC-022).
        "/internal/discord/link",
      })
  @DisplayName("a bearer-only write is never refused for a missing CSRF token")
  void bearerWritesAreExempt(String uri) {
    assertThat(EXEMPT.matches(write("POST", uri)))
        .as(
            "%s must be CSRF-exempt: this chain is stateless and bearer-only, so a client that"
                + " cannot hold a cookie is the only kind there is",
            uri)
        .isTrue();
  }

  @Test
  @DisplayName("the exemption is stated as the API surface, not as a list of endpoints")
  void theExemptionCoversTheWholeApi() {
    // The regression came from a per-endpoint list that a new endpoint silently fell outside of.
    // Naming the surface is what stops the next one repeating it, so the shape is asserted and not
    // merely its current membership.
    assertThat(List.of(SecurityConfig.CSRF_EXEMPT_PATHS)).contains("/api/v1/**");
  }

  @Test
  @DisplayName("paths outside the bearer API are still protected")
  void nonApiPathsAreNotExempt() {
    // Nothing browser-facing lives on this backend today, but the exemption must stay scoped so
    // that adding something browser-facing does not arrive pre-exempted.
    assertThat(EXEMPT.matches(write("POST", "/actuator/shutdown"))).isFalse();
    assertThat(EXEMPT.matches(write("POST", "/login"))).isFalse();
  }
}
