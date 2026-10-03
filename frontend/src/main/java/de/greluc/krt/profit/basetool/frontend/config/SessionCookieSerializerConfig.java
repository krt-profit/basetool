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

import org.jetbrains.annotations.NotNull;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Makes {@code server.servlet.session.cookie.*} the single source of every attribute of the session
 * cookie Spring Session writes (REQ-SEC-025).
 *
 * <p>Without this bean Spring Session builds its serializer from the servlet {@code
 * SessionCookieConfig}, which carries no {@code SameSite}, so the configured value would be
 * ignored.
 */
@Configuration
public class SessionCookieSerializerConfig {

  /**
   * Provides the session {@link CookieSerializer} that Spring Session's {@code
   * SessionRepositoryFilter} writes and reads the session cookie with.
   *
   * @param serverProperties the bound {@code server.*} properties holding the session cookie
   * @return the serializer built by {@link #sessionCookieSerializer(Cookie)}
   */
  @NotNull
  @Bean
  public CookieSerializer cookieSerializer(@NotNull ServerProperties serverProperties) {
    return sessionCookieSerializer(serverProperties.getServlet().getSession().getCookie());
  }

  /**
   * Builds a {@link DefaultCookieSerializer} from the configured cookie, copying each attribute
   * that is set and keeping the serializer's default for each one that is not.
   *
   * <p>The cookie value stays Base64-encoded, so session cookies issued before this serializer
   * remain readable.
   *
   * @param cookie the configured session cookie ({@code server.servlet.session.cookie})
   * @return the serializer; never {@code null}
   */
  @NotNull
  static DefaultCookieSerializer sessionCookieSerializer(@NotNull Cookie cookie) {
    DefaultCookieSerializer serializer = new DefaultCookieSerializer();
    if (cookie.getName() != null) {
      serializer.setCookieName(cookie.getName());
    }
    if (cookie.getDomain() != null) {
      serializer.setDomainName(cookie.getDomain());
    }
    if (cookie.getPath() != null) {
      serializer.setCookiePath(cookie.getPath());
    }
    if (cookie.getHttpOnly() != null) {
      serializer.setUseHttpOnlyCookie(cookie.getHttpOnly());
    }
    if (cookie.getSecure() != null) {
      serializer.setUseSecureCookie(cookie.getSecure());
    }
    if (cookie.getMaxAge() != null) {
      serializer.setCookieMaxAge(Math.toIntExact(cookie.getMaxAge().toSeconds()));
    }
    if (cookie.getSameSite() != null) {
      serializer.setSameSite(cookie.getSameSite().attributeValue());
    }
    if (cookie.getPartitioned() != null) {
      serializer.setPartitioned(cookie.getPartitioned());
    }
    return serializer;
  }
}
