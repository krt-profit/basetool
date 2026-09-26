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

import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Contributes the miscellaneous layout model attributes: {@code appTitle}, {@code
 * unreadNotificationCount} and {@code currentRequestUri}.
 *
 * <p>Scoped to {@link UsesLayoutModel}; the notification count comes from the shared {@link
 * LayoutContextLoader} read, and a backend error hides the badge.
 */
@ControllerAdvice(annotations = UsesLayoutModel.class)
@RequiredArgsConstructor
public class LayoutMiscAdvice {

  /** Reads the request's layout context once and shares it with the other layout advices. */
  private final LayoutContextLoader layoutContextLoader;

  /** Resolves the localised title patterns. */
  private final MessageSource messageSource;

  /**
   * Composes the application title for the {@code <title>} tag and sidebar brand (REQ-ORG-024):
   * "Profit Basetool – &lt;shorthand or name&gt;" for an active org unit, "– Alle Staffeln" for an
   * admin without a pin, otherwise plain "Profit Basetool", localised via {@link
   * LocaleContextHolder}.
   *
   * @param activeOrgUnit the active org unit (Staffel or SK), or {@code null}
   * @param isAllSquadronsMode whether the viewer is an admin without a selection
   * @return the rendered title, never {@code null}
   */
  @ModelAttribute("appTitle")
  public String appTitle(
      @ModelAttribute("activeOrgUnit") OrgUnitMembershipOptionDto activeOrgUnit,
      @ModelAttribute("isAllSquadronsMode") boolean isAllSquadronsMode) {
    Locale locale = LocaleContextHolder.getLocale();
    if (activeOrgUnit != null) {
      String label =
          activeOrgUnit.orgUnitShorthand() != null
              ? activeOrgUnit.orgUnitShorthand()
              : activeOrgUnit.orgUnitName();
      return messageSource.getMessage("app.title.with.squadron", new Object[] {label}, locale);
    }
    if (isAllSquadronsMode) {
      String allLabel = messageSource.getMessage("squadron.switcher.all", null, locale);
      return messageSource.getMessage("app.title.all.squadrons", new Object[] {allLabel}, locale);
    }
    return messageSource.getMessage("app.title", null, locale);
  }

  /**
   * The caller's unread-notification count for the bell badge (REQ-NOTIF-006), taken from the
   * shared {@link LayoutContextLoader} read.
   *
   * @param request the current request, through which the layout context is memoised
   * @return the unread count, or {@code 0} when unauthenticated, not needed, or on a backend error
   */
  @ModelAttribute("unreadNotificationCount")
  public long unreadNotificationCount(HttpServletRequest request) {
    return layoutContextLoader.load(request).unreadNotifications();
  }

  /**
   * The request URI the sidebar switcher form posts back as {@code _referer}, so the redirect after
   * a squadron change returns to the same page.
   *
   * @param request the current request; never {@code null}
   * @return the path and query of the current request, or {@code "/"} as a fallback
   */
  @ModelAttribute("currentRequestUri")
  public String currentRequestUri(HttpServletRequest request) {
    if (request == null) {
      return "/";
    }
    String uri = request.getRequestURI();
    String query = request.getQueryString();
    if (uri == null || uri.isBlank()) {
      return "/";
    }
    return query != null && !query.isBlank() ? uri + "?" + query : uri;
  }
}
