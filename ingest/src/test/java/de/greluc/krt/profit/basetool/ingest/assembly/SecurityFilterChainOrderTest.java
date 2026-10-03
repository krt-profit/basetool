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

package de.greluc.krt.profit.basetool.ingest.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the gateway's security filter chains through {@link FilterChainProxy} as the test profile
 * builds them (REQ-XCH-036): the chains in the order the proxy consults them, each chain's request
 * matcher and the exact ordered list of its filters, the exchange gates included. A re-package or a
 * dependency upgrade that reorders, adds or drops a filter fails here; {@link
 * SecurityFilterChainProductionShapeTest} pins the chains with the management port and the scrape
 * credentials set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class SecurityFilterChainOrderTest {

  /**
   * The main chain's filters, outermost first, the exchange gates between authentication and the
   * rest.
   */
  static final List<String> MAIN_CHAIN =
      List.of(
          "DisableEncodeUrlFilter",
          "WebAsyncManagerIntegrationFilter",
          "SecurityContextHolderFilter",
          "HeaderWriterFilter",
          "CorsFilter",
          "CsrfFilter",
          "LogoutFilter",
          "OAuth2ProtectedResourceMetadataFilter",
          "IdentityProviderUnavailableFilter",
          "BearerTokenAuthenticationFilter",
          "AuthenticationFilter",
          "UserIdMdcFilter",
          "ExchangeTokenGateFilter",
          "ExchangeGateFilter",
          "ExchangeLimitFilter",
          "ExchangeIdempotencyFilter",
          "RequestCacheAwareFilter",
          "SecurityContextHolderAwareRequestFilter",
          "AnonymousAuthenticationFilter",
          "SessionManagementFilter",
          "ExceptionTranslationFilter",
          "AuthorizationFilter");

  /** The chains in the order the proxy consults them, each with its filters, outermost first. */
  private static final List<String> EXPECTED =
      List.of(
          "Or [PathPattern [/actuator/prometheus]] -> DisableEncodeUrlFilter,"
              + " WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter,"
              + " HeaderWriterFilter, CsrfFilter, LogoutFilter,"
              + " SecurityContextHolderAwareRequestFilter, AnonymousAuthenticationFilter,"
              + " SessionManagementFilter, ExceptionTranslationFilter, AuthorizationFilter",
          "any request -> " + String.join(", ", MAIN_CHAIN));

  @Autowired private FilterChainProxy filterChainProxy;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;

  @Test
  void everyChainHoldsExactlyTheExpectedFiltersInOrder() {
    assertThat(describe(filterChainProxy.getFilterChains())).isEqualTo(EXPECTED);
  }

  @Test
  void aSwappedExchangeGateIsCaught() {
    List<String> swapped = new ArrayList<>(MAIN_CHAIN);
    swapped.set(swapped.indexOf("ExchangeGateFilter"), "ExchangeLimitFilter");
    swapped.set(swapped.lastIndexOf("ExchangeLimitFilter"), "ExchangeGateFilter");
    List<String> expected =
        List.of(EXPECTED.get(0), "any request -> " + String.join(", ", swapped));
    assertThatThrownBy(
            () -> assertThat(describe(filterChainProxy.getFilterChains())).isEqualTo(expected))
        .isInstanceOf(AssertionError.class);
  }

  /**
   * Describes the chains, one line per chain.
   *
   * @param chains the proxy's chains
   * @return {@code matcher -> FilterA, FilterB, ...}
   */
  static @NotNull List<String> describe(@NotNull List<SecurityFilterChain> chains) {
    List<String> lines = new ArrayList<>();
    for (SecurityFilterChain chain : chains) {
      List<String> filters = new ArrayList<>();
      chain.getFilters().forEach(filter -> filters.add(filter.getClass().getSimpleName()));
      String matcher =
          chain instanceof DefaultSecurityFilterChain defined
              ? String.valueOf(defined.getRequestMatcher())
              : chain.getClass().getSimpleName();
      lines.add(matcher + " -> " + String.join(", ", filters));
    }
    return lines;
  }
}
