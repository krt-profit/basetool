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
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
import de.greluc.krt.profit.basetool.backend.support.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link RateLimitingFilter} and registers it for all URLs at very high precedence so
 * abusive callers are rejected before any heavier filter (authentication, ETag, controller
 * dispatch) gets a chance to run.
 */
@Configuration
public class RateLimitingConfig {

  /**
   * Returns the {@link RateLimitingFilter} bean injected into the servlet container registration
   * below.
   *
   * @param properties typed configuration with bucket capacity, refill rate, path patterns and
   *     trusted proxies
   * @param problemProperties RFC&nbsp;7807 base URI used in the 429 problem-detail response body
   * @param messageSource resolves the localized 429 {@code title}/{@code detail} for the response
   *     body
   * @param meterRegistry the Micrometer registry the filter's per-bucket 429 rejection counter uses
   * @return the {@link RateLimitingFilter} bean injected into the servlet container registration
   *     below
   */
  @Bean
  public RateLimitingFilter rateLimitingFilter(
      RateLimitProperties properties,
      AppProblemProperties problemProperties,
      MessageSource messageSource,
      MeterRegistry meterRegistry) {
    return new RateLimitingFilter(properties, problemProperties, messageSource, meterRegistry);
  }

  /**
   * Registers the {@link RateLimitingFilter} for the entire URI space ({@code /*}) very early in
   * the filter chain (highest precedence + 10) so a rejected request never reaches downstream
   * components.
   *
   * @param filter the rate-limiting filter created by {@link #rateLimitingFilter}
   * @return Servlet registration with the order and URL patterns set
   */
  @Bean
  public org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>
      rateLimitingFilterRegistration(RateLimitingFilter filter) {
    org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>
        registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>();
    registration.setFilter(filter);
    registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10);
    registration.addUrlPatterns("/*");
    return registration;
  }

  /**
   * The request-body-size cap for the heavy JSON import endpoints (security review, memory-DoS).
   *
   * @param properties the {@code app.request-body-limit.*} configuration
   * @param problemProperties supplies the RFC-7807 {@code type} base URI for the 413 body
   * @param meterRegistry counts each rejection on {@code basetool_request_body_rejected_total}
   * @return the filter (registered by {@link #requestBodySizeLimitFilterRegistration})
   */
  @Bean
  public de.greluc.krt.profit.basetool.backend.filter.RequestBodySizeLimitFilter
      requestBodySizeLimitFilter(
          de.greluc.krt.profit.basetool.backend.support.RequestBodyLimitProperties properties,
          AppProblemProperties problemProperties,
          MeterRegistry meterRegistry) {
    return new de.greluc.krt.profit.basetool.backend.filter.RequestBodySizeLimitFilter(
        properties, problemProperties, meterRegistry);
  }

  /**
   * Registers {@link #requestBodySizeLimitFilter} just after the rate limiter (highest precedence +
   * 15) so an oversized body is refused before Spring Security and MVC binding, but a flood still
   * trips the per-IP rate limit first.
   *
   * @param filter the body-size filter
   * @return the servlet registration
   */
  @Bean
  public org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>
      requestBodySizeLimitFilterRegistration(
          de.greluc.krt.profit.basetool.backend.filter.RequestBodySizeLimitFilter filter) {
    org.springframework.boot.web.servlet.FilterRegistrationBean<jakarta.servlet.Filter>
        registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>();
    registration.setFilter(filter);
    registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 15);
    registration.addUrlPatterns("/*");
    return registration;
  }
}
