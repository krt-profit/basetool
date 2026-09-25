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

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Registers {@link ShallowEtagHeaderFilter} only on {@link #ETAG_URL_PATTERNS}, the routes whose
 * response can carry an ETag (ADR-0161).
 *
 * <p>These are {@code /manifest.webmanifest} and {@code /.well-known/assetlinks.json}; the
 * immutable, content-hashed static assets served by {@link WebMvcConfig} are excluded.
 */
@Configuration
public class EtagConfig {

  /**
   * The servlet URL patterns the ETag filter is registered on: the two publicly cacheable,
   * non-hashed responses whose revalidation an ETag turns into a body-less {@code 304}.
   */
  @Unmodifiable
  public static final List<String> ETAG_URL_PATTERNS =
      List.of("/manifest.webmanifest", "/.well-known/assetlinks.json");

  /**
   * Registers the {@link ShallowEtagHeaderFilter} on {@link #ETAG_URL_PATTERNS} at near-highest
   * precedence, so an {@code If-None-Match} match becomes a {@code 304} before any other filter
   * spends work on the body.
   *
   * @return the filter registration, limited to the cacheable routes
   */
  @NotNull
  @Bean
  public FilterRegistrationBean<ShallowEtagHeaderFilter> shallowEtagHeaderFilter() {
    FilterRegistrationBean<ShallowEtagHeaderFilter> filter = new FilterRegistrationBean<>();
    filter.setFilter(new ShallowEtagHeaderFilter());
    filter.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    filter.setUrlPatterns(ETAG_URL_PATTERNS);
    return filter;
  }
}
