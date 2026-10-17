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

package de.greluc.krt.profit.basetool.backend.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.platform.api.RateLimitProperties;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Asserts that {@link ClientIpContextFilter} sits at the very front of the chain and that Spring's
 * {@link ForwardedHeaderFilter} reaches it from nowhere (ADR-0272, REQ-SEC-011).
 *
 * <p>Downstream of that filter {@code getRemoteAddr()} is the leftmost entry of the forwarded
 * chain, which any client can set.
 */
class ForwardedHeaderConfigTest {

  private final ForwardedHeaderConfig config = new ForwardedHeaderConfig();

  /**
   * Builds the client-IP registration the way the application context does.
   *
   * @return the registration under test.
   */
  private FilterRegistrationBean<ClientIpContextFilter> clientIpRegistration() {
    return config.clientIpContextFilter(boundProperties());
  }

  /**
   * The rate-limit settings the configuration reads its trusted-proxy list from.
   *
   * @return settings trusting one fixture network
   */
  private static RateLimitProperties boundProperties() {
    return BoundProperties.bind(
        RateLimitProperties.class, "trusted-proxies", List.of("10.0.0.0/24"));
  }

  @Test
  @DisplayName("client-IP resolution runs at the very highest precedence, over the whole URI space")
  void clientIpResolutionRunsFirst() {
    FilterRegistrationBean<ClientIpContextFilter> registration = clientIpRegistration();

    assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    assertThat(registration.getUrlPatterns()).containsExactly("/*");
  }

  @Test
  @DisplayName("the configuration puts no ForwardedHeaderFilter in front of the application")
  void noForwardedHeaderFilterReachesTheChain() {
    new ApplicationContextRunner()
        .withBean(RateLimitProperties.class, ForwardedHeaderConfigTest::boundProperties)
        .withUserConfiguration(ForwardedHeaderConfig.class)
        .run(
            context ->
                assertThat(context)
                    .as(
                        "registering Spring's ForwardedHeaderFilter hands every downstream"
                            + " component a client-chosen getRemoteAddr(); the backend builds no"
                            + " absolute URL from a request, so there is nothing to buy with that"
                            + " (ADR-0272)")
                    .hasNotFailed()
                    .doesNotHaveBean(ForwardedHeaderFilter.class)
                    .getBeans(FilterRegistrationBean.class)
                    .values()
                    .allSatisfy(
                        registration ->
                            assertThat(((FilterRegistrationBean<?>) registration).getFilter())
                                .isNotInstanceOf(ForwardedHeaderFilter.class)));
  }

  @Test
  @DisplayName("the only filter the configuration registers is the client-IP resolution")
  void theOnlyRegisteredFilterIsTheClientIpResolution() {
    new ApplicationContextRunner()
        .withBean(RateLimitProperties.class, ForwardedHeaderConfigTest::boundProperties)
        .withUserConfiguration(ForwardedHeaderConfig.class)
        .run(
            context -> {
              List<Filter> filters =
                  context.getBeansOfType(FilterRegistrationBean.class).values().stream()
                      .map(registration -> (Filter) registration.getFilter())
                      .toList();
              assertThat(filters).singleElement().isInstanceOf(ClientIpContextFilter.class);
            });
  }
}
