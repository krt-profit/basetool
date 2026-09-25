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

import java.time.Duration;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/** Spring configuration for Locale. */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

  /**
   * Cookie-based {@link LocaleResolver}: persists the user's locale in the {@code KRT_LOCALE}
   * cookie (default German, 90-day (3-month) max-age, path {@code /}).
   */
  @NotNull
  @Bean
  public LocaleResolver localeResolver() {
    CookieLocaleResolver clr = new CookieLocaleResolver("KRT_LOCALE");
    clr.setDefaultLocale(Locale.GERMAN);
    clr.setCookieMaxAge(Duration.ofDays(90));
    clr.setCookiePath("/");
    return clr;
  }

  /**
   * Interceptor switching the locale when the request carries a {@code ?lang=…} query param.
   *
   * <p>Registered for every path, so it is also the first thing every public, probed endpoint
   * meets.
   */
  @NotNull
  @Bean
  public LocaleChangeInterceptor localeChangeInterceptor() {
    LocaleChangeInterceptor lci = new LocaleChangeInterceptor();
    lci.setParamName("lang");
    lci.setIgnoreInvalidLocale(true);
    return lci;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(localeChangeInterceptor());
  }
}
