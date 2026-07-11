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

import de.greluc.krt.profit.basetool.frontend.model.dto.NotificationCountResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Cross-cutting advice for the miscellaneous chrome model attributes that the layout fragments read
 * on every render but that do not belong to the OrgUnit context or the capability gates: the
 * dynamic {@code appTitle}, the always-on {@code unreadNotificationCount} bell badge, and the
 * {@code currentRequestUri} the switcher form posts back to.
 *
 * <p>{@code appTitle} depends on the active OrgUnit context ({@code activeOrgUnit} + {@code
 * isAllSquadronsMode}) which is produced by {@code OrgUnitContextAdvice} and cross-injected here by
 * name — Spring's {@code ModelFactory} orders {@code @ModelAttribute} methods by dependency across
 * all advice beans, so the reference resolves regardless of the source bean.
 *
 * <p>The backend round-trip for the notification count degrades gracefully: a hiccup hides the
 * badge (count 0) rather than breaking the chrome.
 */
@ControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class LayoutMiscAdvice {

  private final BackendApiClient backendApiClient;
  private final MessageSource messageSource;
  private final FrontendAuthHelperService authHelper;

  /**
   * Composes the dynamic application title rendered in the {@code <title>} tag and the sidebar
   * brand logo — the single place the active OrgUnit context surfaces to the user (REQ-ORG-010; the
   * previously-redundant top-right context chip was removed). Resolution:
   *
   * <ul>
   *   <li>An active pin of <em>either</em> kind ({@code SQUADRON} or {@code SPECIAL_COMMAND}) →
   *       "Profit Basetool – &lt;shorthand&gt;", falling back to the OrgUnit name when it carries
   *       no shorthand. Reading from {@code activeOrgUnit} (the merged Staffel + SK catalogue) —
   *       rather than the Squadron-only {@code activeSquadron} — is what lets an SK pin show in the
   *       title at all; the chip used to be the only surface that did.
   *   <li>Admin in all-OrgUnits mode (no pin) → "Profit Basetool – Alle Staffeln".
   *   <li>No context (squadron-less non-admin, anonymous) → plain "Profit Basetool".
   * </ul>
   *
   * <p>The active OrgUnit context ({@code activeOrgUnit} + {@code isAllSquadronsMode}) is produced
   * by {@code OrgUnitContextAdvice} and cross-injected here by name. Resolution uses the request
   * locale via {@link LocaleContextHolder} so the suffix is localised consistently with the rest of
   * the page (the message-format pattern {@code {0}} is filled with the OrgUnit shorthand/name or
   * the localised "all squadrons" label). The {@code app.title.with.squadron} key name predates SK
   * support and is kept generic — it now serves any OrgUnit kind.
   *
   * @param activeOrgUnit resolved active OrgUnit (Staffel or SK), or {@code null}.
   * @param isAllSquadronsMode whether the current viewer is an admin without a selection.
   * @return the rendered title string, never {@code null}.
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
   * The caller's unread-notification count, fed to the always-on bell badge rendered on every page
   * (REQ-NOTIF-006). Resolved once per request; fails soft to zero so a backend hiccup hides the
   * badge rather than breaking the chrome, and the bell's client-side polling keeps it fresh after
   * the initial render.
   *
   * @return the unread count, or {@code 0} when unauthenticated or on a backend error.
   */
  @ModelAttribute("unreadNotificationCount")
  public long unreadNotificationCount() {
    if (!authHelper.isAuthenticated()) {
      return 0L;
    }
    try {
      NotificationCountResponse resp =
          backendApiClient.get(
              "/api/v1/notifications/unread-count", NotificationCountResponse.class);
      return resp != null && resp.count() != null ? resp.count() : 0L;
    } catch (Exception ex) {
      log.debug("Failed to resolve unread notification count", ex);
      return 0L;
    }
  }

  /**
   * The request URI the sidebar switcher form posts back as {@code _referer} so the redirect after
   * the squadron change lands the user on the same page they were on. We resolve it via a model
   * attribute rather than the Thymeleaf {@code #httpServletRequest} utility because the latter is
   * not exposed in every render context (MockMvc tests in particular).
   *
   * @param request the current HTTP servlet request injected by Spring; never {@code null}.
   * @return the path + query of the current request, or {@code "/"} as a defensive fallback.
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
