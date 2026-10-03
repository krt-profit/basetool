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

import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the gateway's security filter chains through {@link FilterChainProxy} in the shape
 * production runs them (REQ-XCH-036): the open management-port chain first, the basic-auth scrape
 * chain second, the main chain with the exchange gates last.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "app.monitoring.scrape.username=metrics-scraper",
      "app.monitoring.scrape.password=test-scrape-password"
    })
class SecurityFilterChainProductionShapeTest {

  /** The chains in the order the proxy consults them, each with its filters, outermost first. */
  private static final List<String> EXPECTED =
      List.of(
          "Or [PathPattern [/actuator/**]] -> DisableEncodeUrlFilter,"
              + " WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter,"
              + " HeaderWriterFilter, CsrfFilter, LogoutFilter,"
              + " SecurityContextHolderAwareRequestFilter, AnonymousAuthenticationFilter,"
              + " SessionManagementFilter, ExceptionTranslationFilter, AuthorizationFilter",
          "Or [PathPattern [/actuator/prometheus]] -> DisableEncodeUrlFilter,"
              + " WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter,"
              + " HeaderWriterFilter, CsrfFilter, LogoutFilter, BasicAuthenticationFilter,"
              + " SecurityContextHolderAwareRequestFilter, AnonymousAuthenticationFilter,"
              + " SessionManagementFilter, ExceptionTranslationFilter, AuthorizationFilter",
          "any request -> " + String.join(", ", SecurityFilterChainOrderTest.MAIN_CHAIN));

  @Autowired private FilterChainProxy filterChainProxy;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;

  @Test
  void everyChainHoldsExactlyTheExpectedFiltersInOrder() {
    assertThat(SecurityFilterChainOrderTest.describe(filterChainProxy.getFilterChains()))
        .isEqualTo(EXPECTED);
  }
}
