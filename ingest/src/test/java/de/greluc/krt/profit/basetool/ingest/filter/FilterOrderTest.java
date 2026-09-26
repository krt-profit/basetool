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

package de.greluc.krt.profit.basetool.ingest.filter;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.service.BackendImportClient;
import de.greluc.krt.profit.basetool.ingest.service.HandoffStagingService;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Pins the gateway's servlet-filter order as registered by Boot's {@link
 * ServletContextInitializerBeans}: no two filters share an order, {@link RateLimitingFilter} runs
 * before {@link PayloadSizeLimitFilter}, and every filter runs before the Spring Security chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FilterOrderTest {

  /** The order the gateway's own servlet filters must be registered in, outermost first. */
  private static final List<Class<? extends Filter>> EXPECTED_ORDER =
      List.of(
          CorrelationIdFilter.class,
          BotProtectionFilter.class,
          RequestLoggingFilter.class,
          RateLimitingFilter.class,
          PayloadSizeLimitFilter.class);

  /** Boot's registration name for the Spring Security filter chain. */
  private static final String SECURITY_CHAIN = "springSecurityFilterChain";

  @Autowired private ApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private BackendImportClient backendImportClient;
  @MockitoBean private HandoffStagingService handoffStagingService;

  @Test
  void theGatewayFiltersAreRegisteredInTheIntendedOrderAheadOfSpringSecurity() {
    List<String> registered = new ArrayList<>();
    Map<String, Integer> orderByName = new HashMap<>();
    for (ServletContextInitializer initializer : new ServletContextInitializerBeans(context)) {
      if (initializer instanceof AbstractFilterRegistrationBean<?> registration) {
        String name = registration.getFilterName();
        registered.add(name);
        orderByName.put(name, registration.getOrder());
      }
    }

    List<String> expected = EXPECTED_ORDER.stream().map(FilterOrderTest::nameOf).toList();
    assertThat(registered).containsSubsequence(expected);
    assertThat(registered).contains(SECURITY_CHAIN);
    int securityIndex = registered.indexOf(SECURITY_CHAIN);
    for (String filter : expected) {
      assertThat(registered.indexOf(filter))
          .as("%s must run before the Spring Security chain", filter)
          .isLessThan(securityIndex);
    }
    assertThat(expected.stream().map(orderByName::get).distinct().count())
        .as("no two gateway filters may share an order; a tie is decided by accident")
        .isEqualTo(expected.size());
  }

  @Test
  void theOrderConstantsAreDistinctAndMatchTheDocumentedSlots() {
    assertThat(CorrelationIdFilter.ORDER).isLessThan(BotProtectionFilter.ORDER);
    assertThat(BotProtectionFilter.ORDER).isLessThan(RequestLoggingFilter.ORDER);
    assertThat(RequestLoggingFilter.ORDER).isLessThan(RateLimitingFilter.ORDER);
    assertThat(RateLimitingFilter.ORDER)
        .as("throttle before the size cap buffers a chunked body")
        .isLessThan(PayloadSizeLimitFilter.ORDER);
  }

  /**
   * The registration name Boot derives for a filter bean: the bean name, i.e. the decapitalised
   * class name for a {@code @Component}.
   *
   * @param type the filter class
   * @return its registration name
   */
  private static String nameOf(Class<? extends Filter> type) {
    String simple = type.getSimpleName();
    return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
  }
}
