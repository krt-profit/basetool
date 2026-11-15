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

package de.greluc.krt.profit.basetool.frontend.kernel.security;

import jakarta.servlet.http.HttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Marks the request that renders a page the session gates keep the browser on, a gate's own page or
 * a legal page, where a gate's refusal of a page section falls back instead of handing the browser
 * to a gate's page (ADR-0385, REQ-UI-021).
 */
public final class GatePageRequest {

  /** The request attribute {@link #mark} sets. */
  private static final String ATTRIBUTE = GatePageRequest.class.getName() + ".marked";

  private GatePageRequest() {}

  /**
   * Marks a request as rendering a gate's page, as {@link SessionGates#isGatePage} decides it, or a
   * legal page.
   *
   * @param request the request {@link SessionGateFilter} passes on
   */
  public static void mark(@NotNull HttpServletRequest request) {
    request.setAttribute(ATTRIBUTE, Boolean.TRUE);
  }

  /**
   * Tells whether the request bound to the current thread renders a gate's page or a legal page.
   *
   * @return {@code true} only inside a request {@link #mark} marked
   */
  public static boolean isCurrent() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    return attributes != null
        && Boolean.TRUE.equals(attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
  }

  /**
   * Tells whether a request renders a gate's page or a legal page.
   *
   * @param request the request
   * @return {@code true} only for a request {@link #mark} marked
   */
  public static boolean isMarked(@NotNull HttpServletRequest request) {
    return Boolean.TRUE.equals(request.getAttribute(ATTRIBUTE));
  }
}
