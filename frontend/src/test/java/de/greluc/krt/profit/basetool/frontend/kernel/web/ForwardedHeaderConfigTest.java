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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Verifies that {@link ForwardedHeaderFilter} runs strictly after {@link ClientIpContextFilter}, so
 * the latter still sees the raw {@code X-Forwarded-For} chain, and that it applies only the {@code
 * X-Forwarded-*} family the edge sends.
 */
class ForwardedHeaderConfigTest {

  private final ForwardedHeaderConfig config = new ForwardedHeaderConfig();

  /**
   * Runs the registered filter over a request to {@code /missions} and returns the request the
   * chain behind it sees.
   *
   * @param request the incoming request
   * @return the request as the application sees it
   * @throws Exception if the filter fails
   */
  private HttpServletRequest filtered(MockHttpServletRequest request) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    config
        .forwardedHeaderFilter()
        .getFilter()
        .doFilter(request, new MockHttpServletResponse(), chain);
    ServletRequest seen = chain.getRequest();
    assertThat(seen).isInstanceOf(HttpServletRequest.class);
    return (HttpServletRequest) seen;
  }

  @Test
  void aStandardForwardedHeaderIsHiddenAndNeverApplied() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missions");
    request.addHeader("Forwarded", "for=203.0.113.7;proto=https;host=spoofed.example");

    HttpServletRequest seen = filtered(request);

    assertThat(seen.getServerName()).as("host from Forwarded").isEqualTo("localhost");
    assertThat(seen.getScheme()).as("proto from Forwarded").isEqualTo("http");
    assertThat(seen.getRemoteAddr()).as("for from Forwarded").isEqualTo("127.0.0.1");
    assertThat(seen.getHeader("Forwarded")).as("the header is hidden from the app").isNull();
  }

  @Test
  void theXForwardedFamilyTheEdgeSendsIsApplied() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missions");
    request.addHeader("X-Forwarded-Host", "basetool.example");
    request.addHeader("X-Forwarded-Proto", "https");
    request.addHeader("X-Forwarded-For", "203.0.113.7");

    HttpServletRequest seen = filtered(request);

    assertThat(seen.getServerName()).isEqualTo("basetool.example");
    assertThat(seen.getScheme()).isEqualTo("https");
    assertThat(seen.getRemoteAddr()).isEqualTo("203.0.113.7");
  }

  @Test
  void anXForwardedPrefixIsNeverApplied() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missions");
    request.addHeader("X-Forwarded-Host", "basetool.example");
    request.addHeader("X-Forwarded-Prefix", "/spoofed");

    HttpServletRequest seen = filtered(request);

    assertThat(seen.getContextPath()).isEmpty();
    assertThat(seen.getRequestURI()).isEqualTo("/missions");
  }

  @Test
  void forwardedHeaderFilter_runsExactlyOneSlotAfterClientIpContextFilter() {
    FilterRegistrationBean<ForwardedHeaderFilter> registration = config.forwardedHeaderFilter();
    int clientIpFilterOrder =
        new ClientIpContextFilter(new ClientIpProperties(List.of())).getOrder();

    assertThat(clientIpFilterOrder)
        .as("ClientIpContextFilter must be at the very highest precedence")
        .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    assertThat(registration.getOrder())
        .as("ForwardedHeaderFilter must run strictly after ClientIpContextFilter")
        .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1)
        .isGreaterThan(clientIpFilterOrder);
  }

  @Test
  void forwardedHeaderFilter_registersAForwardedHeaderFilter() {
    FilterRegistrationBean<ForwardedHeaderFilter> registration = config.forwardedHeaderFilter();

    assertThat(registration.getFilter())
        .as("mirrors Spring Boot's own registration so OAuth2/HSTS behaviour is unchanged")
        .isInstanceOf(ForwardedHeaderFilter.class);
  }
}
