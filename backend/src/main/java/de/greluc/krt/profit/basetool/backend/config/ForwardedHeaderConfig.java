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
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.server.autoconfigure.servlet.ForwardedHeaderFilterCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Orders {@link ClientIpContextFilter} before Spring's {@link ForwardedHeaderFilter}, so client-IP
 * attribution sees the raw proxy headers (REQ-SEC-011).
 *
 * <p>Requires {@code server.forward-headers-strategy: none}; the forwarded-header filter is
 * re-registered here at {@link Ordered#HIGHEST_PRECEDENCE} {@code + 1}.
 */
@Configuration
public class ForwardedHeaderConfig {

  /**
   * Registers the client-IP resolution filter at the front of the chain; the order is set on the
   * registration, which is what the servlet container sorts by.
   *
   * @param properties supplies the trusted-proxy allowlist the resolution honours.
   * @return the registration, ordered at {@link Ordered#HIGHEST_PRECEDENCE}.
   */
  @NotNull
  @Bean
  public FilterRegistrationBean<ClientIpContextFilter> clientIpContextFilter(
      @NotNull RateLimitProperties properties) {
    FilterRegistrationBean<ClientIpContextFilter> registration =
        new FilterRegistrationBean<>(new ClientIpContextFilter(properties.trustedProxies()));
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  /**
   * Registers Spring's forwarded-header filter one slot behind the client-IP resolution, with the
   * same dispatcher types and {@link ForwardedHeaderFilterCustomizer} hook as Boot's own
   * registration.
   *
   * @param customizerProvider the optional customizer applied to the filter.
   * @return the registration, ordered at {@link Ordered#HIGHEST_PRECEDENCE} {@code + 1}.
   */
  @NotNull
  @Bean
  public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter(
      ObjectProvider<ForwardedHeaderFilterCustomizer> customizerProvider) {
    ForwardedHeaderFilter filter = new ForwardedHeaderFilter();
    customizerProvider.ifAvailable(customizer -> customizer.customize(filter));
    FilterRegistrationBean<ForwardedHeaderFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setDispatcherTypes(
        DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }
}
