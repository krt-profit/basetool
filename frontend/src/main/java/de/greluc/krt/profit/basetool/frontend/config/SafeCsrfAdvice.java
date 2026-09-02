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
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * The CSRF token and its header name, exposed so that reading them can never break a render.
 *
 * <p><strong>Why this exists.</strong> {@code fragments/head.html} used to write the two CSRF meta
 * tags straight from {@code ${_csrf.token}}, guarded by {@code th:if="${_csrf != null}"}. That
 * guard looks like it covers the failure case and does not: since Spring Security 6 the request
 * attribute holds a <em>deferred</em> token, so {@code _csrf} is never null — it is a supplier
 * whose <em>getter</em> loads the token from the session, and a session that cannot be read makes
 * that getter throw. A null check cannot catch a throwing method.
 *
 * <p>On 2026-09-02 that turned a recoverable fault into an unrecoverable one. Unreadable session
 * data made every request 500; the {@code error/500} view then rendered the same shared head,
 * re-entered the same broken session, and threw again — so the error page produced no body at all
 * and the browser showed a blank white page. There was no status text, no correlation id, nothing
 * to search for. Diagnosis took an hour and a half for a fault the error page could have named in
 * one line.
 *
 * <p><strong>The rule this encodes: the error page must not be able to fail.</strong> An error view
 * that depends on the very subsystem that failed is not a diagnostic, it is a second outage on top
 * of the first. Reading the token through this advice means the worst case is a page rendered
 * without CSRF metas — which is exactly right for an error page, because it carries no form.
 *
 * <p>The advice is deliberately <em>not</em> limited to the error views. Every page benefits: a
 * template can no longer be taken down by a token accessor, wherever the session came from.
 */
@Slf4j
@ControllerAdvice
public class SafeCsrfAdvice {

  /**
   * The CSRF token value for the current request, or {@code null} when it cannot be produced.
   *
   * <p>Reads the deferred token from the request attribute Spring Security publishes and forces it,
   * catching anything the underlying supplier throws — a session-backed repository can fail at this
   * point for reasons that have nothing to do with the page being rendered.
   *
   * @param request the current request; Spring supplies it.
   * @return the token, or {@code null} when there is none or it could not be loaded.
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
   * Forces the deferred token, swallowing any failure.
   *
   * <p>Both accessors call this rather than sharing one model attribute of type {@link CsrfToken}:
   * a {@link CsrfToken} in the model would put the throwing getter back into the template, which is
   * the defect being removed. Only already-resolved {@link String}s reach Thymeleaf.
   *
   * @param request the current request.
   * @return a fully-resolved token, or {@code null} when none is available.
   */
  private static @Nullable CsrfToken resolve(HttpServletRequest request) {
    Object attribute = request.getAttribute(CsrfToken.class.getName());
    if (!(attribute instanceof CsrfToken token)) {
      return null;
    }
    try {
      // Forces the deferred supplier. This is the call that threw during the outage.
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
