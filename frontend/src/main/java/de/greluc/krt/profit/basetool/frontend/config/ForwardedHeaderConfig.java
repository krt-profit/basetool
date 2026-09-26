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
 * Registers Spring's {@link ForwardedHeaderFilter} at {@link Ordered#HIGHEST_PRECEDENCE} + 1, so
 * {@code ClientIpContextFilter} reads the raw forwarded headers first.
 *
 * <p>Requires {@code server.forward-headers-strategy: none}; the registration otherwise mirrors
 * Spring Boot's own.
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
