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
 * Registers Spring's {@link ShallowEtagHeaderFilter} on the routes whose response can actually
 * carry an ETag, and on nothing else (FE-PERF-03, ADR-0161 §8.3).
 *
 * <p>The filter buffers the whole response body in memory before a byte reaches the client, and
 * only then decides whether to hash it. Spring refuses to generate an ETag once {@code
 * Cache-Control} carries {@code no-store} ({@code isEligibleForEtag}), and Spring Security's
 * default cache-control writer puts {@code no-store} on every response that has not set its own
 * header. So on every page, fragment, JSON write and the notification SSE relay the filter did the
 * buffering and never produced an ETag — pure cost, paid on each render, and a streaming response
 * only escaped it because Spring MVC's emitter handler opts out of the buffer per request.
 *
 * <p>The routes that set their own cacheable {@code Cache-Control}, and therefore keep the ETag and
 * the {@code If-None-Match} → {@code 304} it enables, are exactly {@link #ETAG_URL_PATTERNS}:
 *
 * <ul>
 *   <li>the asset trees {@link WebMvcConfig} serves ({@code public, max-age=31536000, immutable}).
 *       Content-hashed and {@code Last-Modified}-capable, so the ETag is a second revalidation path
 *       rather than the only one; kept because {@code StaticResourcesCachingTest} pins it and
 *       dropping it is a separate decision;
 *   <li>{@code /manifest.webmanifest} ({@code public, max-age=3600}), re-read by browsers hourly;
 *   <li>{@code /.well-known/assetlinks.json} ({@code public, max-age=86400}).
 * </ul>
 *
 * <p>The asset-tree prefixes mirror {@link WebMvcConfig#addResourceHandlers}; {@code
 * StaticResourceHandlerMappingTest} fails when a tree is added there without its prefix here.
 */
@Configuration
public class EtagConfig {

  /**
   * The servlet URL patterns the ETag filter is registered on. Servlet path-prefix patterns ({@code
   * /css/*}) cover a whole tree, so each {@code /tree/**} handler of {@link WebMvcConfig} appears
   * as {@code /tree/*}.
   */
  @Unmodifiable
  public static final List<String> ETAG_URL_PATTERNS =
      List.of(
          "/css/*",
          "/fonts/*",
          "/images/*",
          "/js/*",
          "/logos/*",
          "/robots.txt",
          "/manifest.webmanifest",
          "/.well-known/assetlinks.json");

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
