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

package de.greluc.krt.profit.basetool.frontend.config;

import jakarta.servlet.DispatcherType;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Explicitly registers Spring's {@link ForwardedHeaderFilter} one ordering slot <em>after</em>
 * {@code ClientIpContextFilter}, replacing the framework auto-registration (finding SEC-02).
 *
 * <p><b>Why this is needed:</b> the frontend relies on {@code ForwardedHeaderFilter} to rebuild the
 * external scheme/host from {@code X-Forwarded-Proto}/{@code -Host} so the OAuth2 redirect URI and
 * HSTS are correct — that is why {@code server.forward-headers-strategy} was {@code framework}. But
 * that filter consumes {@code X-Forwarded-For} and exposes only the <em>leftmost</em> (client-
 * controlled) entry, which {@code ClientIpContextFilter} must read <b>raw</b> to attribute the rate
 * limit safely (SEC-02). Spring Boot pins the auto-registered {@code ForwardedHeaderFilter} to
 * {@link Ordered#HIGHEST_PRECEDENCE} ({@code Integer.MIN_VALUE}), and no servlet filter can be
 * ordered before {@code Integer.MIN_VALUE}. So {@code application.yml} sets {@code
 * forward-headers-strategy: none} (suppressing the auto-registration) and this bean re-registers
 * the identical filter at {@code HIGHEST_PRECEDENCE + 1}. Net effect: {@code ClientIpContextFilter}
 * (still at {@code HIGHEST_PRECEDENCE}) runs first on the raw headers, then this filter runs and
 * rewrites scheme/host/remote-addr for OAuth2 and HSTS exactly as the framework strategy did.
 *
 * <p>The registration mirrors Spring Boot's own ({@code new ForwardedHeaderFilter()} over the
 * {@code REQUEST}/{@code ASYNC}/{@code ERROR} dispatcher types); only the order differs by one.
 */
@Configuration
public class ForwardedHeaderConfig {

  /**
   * Registers {@link ForwardedHeaderFilter} at {@link Ordered#HIGHEST_PRECEDENCE} {@code + 1} — as
   * early as Spring Boot's default, but one slot behind {@code ClientIpContextFilter} so the raw
   * {@code X-Forwarded-For} chain is still visible to the client-IP attribution.
   *
   * @return the filter registration for the forwarded-header filter
   */
  @NotNull
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
