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

import de.greluc.krt.profit.basetool.backend.platform.api.RateLimitProperties;
import jakarta.servlet.DispatcherType;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Puts {@link ClientIpContextFilter} at the front of the chain, so client-IP attribution reads the
 * raw proxy headers of the actual TCP peer (REQ-SEC-011).
 *
 * <p>Requires {@code server.forward-headers-strategy: none}, and registers no {@code
 * ForwardedHeaderFilter} (ADR-0272) — downstream of that filter {@code getRemoteAddr()} is
 * client-chosen. A caller needing an absolute URL must build it from configuration.
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
}
