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

import de.greluc.krt.profit.basetool.backend.filter.ClientIpContextFilter;
import de.greluc.krt.profit.basetool.backend.support.RateLimitProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Puts {@link ClientIpContextFilter} in front of Spring's {@link ForwardedHeaderFilter} so
 * client-IP attribution sees the raw proxy chain (REQ-SEC-011).
 *
 * <p><b>Why the auto-registration had to go.</b> The backend needs {@code ForwardedHeaderFilter} —
 * it rebuilds scheme and host so problem-detail {@code instance} URIs, {@code Location} headers and
 * HSTS reflect the external origin rather than the container. That is why {@code
 * server.forward-headers-strategy} used to be {@code framework}. But Spring Boot pins the
 * auto-registered filter to {@link Ordered#HIGHEST_PRECEDENCE} ({@code Integer.MIN_VALUE}), and no
 * servlet filter can be ordered before {@code Integer.MIN_VALUE}. Since that filter overwrites
 * {@code getRemoteAddr()} with the leftmost — client-controlled — {@code X-Forwarded-For} entry and
 * hides the header from everything downstream, nothing placed after it can attribute a request
 * safely. So {@code application.yml} now sets {@code forward-headers-strategy: none} and this class
 * re-registers the identical filter one slot later.
 *
 * <p>Net effect: {@code ClientIpContextFilter} runs at {@code HIGHEST_PRECEDENCE} on the raw
 * headers, this filter runs at {@code HIGHEST_PRECEDENCE + 1} and rewrites scheme, host and
 * remote-addr exactly as the framework strategy did. Every other filter keeps the view it had, and
 * only the attribution changes. This mirrors the frontend configuration of the same name (finding
 * SEC-02).
 */
@Configuration
public class ForwardedHeaderConfig {

  /**
   * Registers the client-IP resolution filter at the very front of the chain.
   *
   * @param properties supplies the trusted-proxy allowlist the resolution honours.
   * @return the registration, ordered at {@link Ordered#HIGHEST_PRECEDENCE}.
   */
  @Bean
  public FilterRegistrationBean<ClientIpContextFilter> clientIpContextFilter(
      RateLimitProperties properties) {
    FilterRegistrationBean<ClientIpContextFilter> registration =
        new FilterRegistrationBean<>(new ClientIpContextFilter(properties));
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  /**
   * Re-registers Spring's forwarded-header filter one slot behind the client-IP resolution.
   *
   * <p>The registration mirrors Spring Boot's own — {@code new ForwardedHeaderFilter()} over the
   * {@code REQUEST}/{@code ASYNC}/{@code ERROR} dispatcher types; only the order differs by one.
   *
   * @return the registration, ordered at {@link Ordered#HIGHEST_PRECEDENCE} {@code + 1}.
   */
  @Bean
  public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
    FilterRegistrationBean<ForwardedHeaderFilter> registration =
        new FilterRegistrationBean<>(new ForwardedHeaderFilter());
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }
}
