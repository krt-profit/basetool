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
 * Pins the servlet-filter order of the gateway as the container will actually register it
 * (ING-SIMP-01), read through Boot's own {@link ServletContextInitializerBeans} — the same
 * machinery that turns the {@code @Component} filters into registrations at startup — rather than
 * by comparing the {@code ORDER} constants with each other, which would only prove the constants
 * are what the constants say.
 *
 * <p>Two defects this guards against, both real:
 *
 * <ul>
 *   <li><b>A tie.</b> {@link BotProtectionFilter} and {@link RequestLoggingFilter} both sat at
 *       {@code HIGHEST_PRECEDENCE + 15}, which left their relative order to bean-registration order
 *       — an accident of classpath scanning, not a decision.
 *   <li><b>Body buffering before throttling.</b> {@link PayloadSizeLimitFilter} ({@code +20}) ran
 *       before {@link RateLimitingFilter} ({@code +30}), so a caller already over their budget
 *       still made the gateway read and hold a chunked body of up to 2&nbsp;MiB per request before
 *       the 429. The limiter now runs first.
 * </ul>
 *
 * <p>Every one of them must also run before the Spring Security chain: the bot filter exists to
 * spare the resource server a JWKS round trip per scanner probe, and the correlation id has to be
 * in the MDC before any security line is logged.
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
