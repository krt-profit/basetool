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

import org.jetbrains.annotations.NotNull;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Wires Spring's {@link ShallowEtagHeaderFilter} so {@code If-None-Match} conditional GETs are
 * short-circuited at the servlet layer — saves bandwidth on unchanged responses without touching
 * controllers.
 */
@Configuration
public class EtagConfig {

  /**
   * Provides the ETag filter as a {@link StreamAwareShallowEtagHeaderFilter}, which leaves
   * Server-Sent-Event streams unbuffered.
   *
   * @return a fresh filter instance that leaves the streaming endpoints alone
   */
  @NotNull
  @Bean
  public ShallowEtagHeaderFilter shallowEtagFilter() {
    return new StreamAwareShallowEtagHeaderFilter();
  }

  /**
   * Registers the ETag filter for all paths at near-highest precedence, so a 304 short-circuits
   * before heavier filters run.
   *
   * @param shallowEtagFilter the filter bean to register
   * @return servlet container registration with URL patterns and order set
   */
  @NotNull
  @Bean
  public FilterRegistrationBean<ShallowEtagHeaderFilter> shallowEtagHeaderFilter(
      ShallowEtagHeaderFilter shallowEtagFilter) {
    FilterRegistrationBean<ShallowEtagHeaderFilter> filter = new FilterRegistrationBean<>();
    filter.setFilter(shallowEtagFilter);
    filter.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    filter.addUrlPatterns("/*");
    return filter;
  }
}
