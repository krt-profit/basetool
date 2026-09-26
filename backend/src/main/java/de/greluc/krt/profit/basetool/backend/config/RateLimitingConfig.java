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

import de.greluc.krt.profit.basetool.backend.filter.RateLimitingFilter;
import de.greluc.krt.profit.basetool.backend.filter.RequestBodySizeLimitFilter;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.RateLimitProperties;
import de.greluc.krt.profit.basetool.backend.support.RequestBodyLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the {@link RateLimitingFilter} and registers it for all URLs at very high precedence so
 * abusive callers are rejected before any heavier filter (authentication, ETag, controller
 * dispatch) gets a chance to run.
 */
@Configuration
public class RateLimitingConfig {

  /**
   * Creates the {@link RateLimitingFilter}.
   *
   * @param properties bucket capacity, refill rate, path patterns and trusted proxies
   * @param problemProperties the RFC&nbsp;7807 base URI for the 429 body
   * @param messageSource resolves the localized 429 title and detail
   * @param meterRegistry the registry for the per-bucket rejection counter
   * @return the rate-limiting filter
   */
  @NotNull
  @Bean
  public RateLimitingFilter rateLimitingFilter(
      RateLimitProperties properties,
      AppProblemProperties problemProperties,
      MessageSource messageSource,
      MeterRegistry meterRegistry) {
    return new RateLimitingFilter(properties, problemProperties, messageSource, meterRegistry);
  }

  /**
   * Registers the {@link RateLimitingFilter} for {@code /*} at highest precedence + 10.
   *
   * @param filter the rate-limiting filter created by {@link #rateLimitingFilter}
   * @return the servlet registration with order and URL patterns set
   */
  @NotNull
  @Bean
  public FilterRegistrationBean<Filter> rateLimitingFilterRegistration(RateLimitingFilter filter) {
    FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
    registration.setFilter(filter);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    registration.addUrlPatterns("/*");
    return registration;
  }

  /**
   * Creates the request-body-size cap for the heavy JSON import endpoints.
   *
   * @param properties the {@code app.request-body-limit.*} configuration
   * @param problemProperties the RFC&nbsp;7807 base URI for the 413 body
   * @param meterRegistry counts rejections on {@code basetool_request_body_rejected_total}
   * @return the filter, registered by {@link #requestBodySizeLimitFilterRegistration}
   */
  @NotNull
  @Bean
  public RequestBodySizeLimitFilter requestBodySizeLimitFilter(
      RequestBodyLimitProperties properties,
      AppProblemProperties problemProperties,
      MeterRegistry meterRegistry) {
    return new RequestBodySizeLimitFilter(properties, problemProperties, meterRegistry);
  }

  /**
   * Registers {@link #requestBodySizeLimitFilter} at highest precedence + 15, after the rate
   * limiter and before Spring Security.
   *
   * @param filter the body-size filter
   * @return the servlet registration
   */
  @NotNull
  @Bean
  public FilterRegistrationBean<Filter> requestBodySizeLimitFilterRegistration(
      RequestBodySizeLimitFilter filter) {
    FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
    registration.setFilter(filter);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
    registration.addUrlPatterns("/*");
    return registration;
  }
}
