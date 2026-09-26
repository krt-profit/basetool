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

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the CSRF token and its header name as model attributes that never throw, so an unreadable
 * session cannot break a render, including the error page.
 *
 * <p>Scoped to {@link UsesLayoutModel} controllers; REST callers read their token from {@code
 * /csrf}.
 */
@Slf4j
@ControllerAdvice(annotations = UsesLayoutModel.class)
public class SafeCsrfAdvice {

  /**
   * The CSRF token value for the current request, forced from the deferred token with any failure
   * swallowed.
   *
   * @param request the current request
   * @return the token, or {@code null} when there is none or it could not be loaded
   */
  @ModelAttribute("csrfTokenValue")
  public @Nullable String csrfTokenValue(HttpServletRequest request) {
    CsrfToken token = resolve(request);
    return token == null ? null : token.getToken();
  }

  /**
   * The header name the token must be sent under, or {@code null} when it cannot be produced.
   *
   * @param request the current request; Spring supplies it.
   * @return the header name, or {@code null}.
   */
  @ModelAttribute("csrfHeaderName")
  public @Nullable String csrfHeaderName(HttpServletRequest request) {
    CsrfToken token = resolve(request);
    return token == null ? null : token.getHeaderName();
  }

  /**
   * Forces the deferred token, swallowing any failure, so only resolved strings reach the template.
   *
   * @param request the current request
   * @return a fully-resolved token, or {@code null} when none is available
   */
  private static @Nullable CsrfToken resolve(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return null;
    }
    Object attribute = request.getAttribute(CsrfToken.class.getName());
    if (!(attribute instanceof CsrfToken token)) {
      return null;
    }
    try {
      token.getToken();
      return token;
    } catch (RuntimeException ex) {
      log.warn(
          "The CSRF token could not be resolved ({}); rendering without CSRF metadata",
          ex.getClass().getSimpleName());
      return null;
    }
  }
}
