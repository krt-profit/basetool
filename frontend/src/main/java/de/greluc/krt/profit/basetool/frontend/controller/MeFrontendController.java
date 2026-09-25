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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Manages the caller's active OrgUnit, stored in the frontend session and relayed to the backend as
 * the {@code X-Active-Org-Unit-Id} header. The backend re-validates the pin against the caller's
 * memberships.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/me")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MeFrontendController {

  /**
   * Frontend session attribute holding the caller's active OrgUnit id, relayed to the backend as
   * {@code X-Active-Org-Unit-Id}.
   */
  public static final String ACTIVE_ORG_UNIT_SESSION_KEY = "iridium.activeOrgUnitId";

  /**
   * Sets the caller's active OrgUnit, or clears it for a blank id, and redirects back.
   *
   * @param orgUnitId the OrgUnit to activate, {@code null} (absent or blank) to clear.
   * @param referer optional redirect target; anything but a same-origin path falls back to {@code
   *     /}.
   * @param request HTTP request injected by Spring; never {@code null}.
   * @param redirectAttributes flash attribute carrier for the success toast.
   * @return redirect view to the referring page so the next render sees the new context.
   */
  @NotNull
  @PostMapping("/active-org-unit")
  public RedirectView setActiveOrgUnit(
      @RequestParam(value = "orgUnitId", required = false) @Nullable UUID orgUnitId,
      @RequestParam(value = "_referer", required = false) @Nullable String referer,
      HttpServletRequest request,
      RedirectAttributes redirectAttributes) {
    return applyActiveOrgUnitSelection(orgUnitId, referer, request, redirectAttributes);
  }

  /**
   * Returns {@code referer} when it is a same-origin path, otherwise {@code /}.
   *
   * <p>Only a path with exactly one leading {@code /}, not followed by a backslash and free of
   * control characters, is accepted.
   *
   * @param referer the raw {@code _referer} form field, may be {@code null}
   * @return {@code referer} when it is a same-origin path, otherwise {@code "/"}
   */
  @Contract(pure = true)
  static @NotNull String safeRedirectTarget(@Nullable String referer) {
    if (referer == null
        || referer.isEmpty()
        || referer.charAt(0) != '/'
        || (referer.length() > 1 && (referer.charAt(1) == '/' || referer.charAt(1) == '\\'))) {
      return "/";
    }
    for (int i = 0; i < referer.length(); i++) {
      if (Character.isISOControl(referer.charAt(i))) {
        return "/";
      }
    }
    return referer;
  }

  /**
   * Stores or clears the active OrgUnit in {@link #ACTIVE_ORG_UNIT_SESSION_KEY} and adds the
   * matching flash toast.
   *
   * @param orgUnitId the OrgUnit id to activate, {@code null} to clear.
   * @param referer optional redirect target, validated by {@link #safeRedirectTarget(String)}.
   * @param request HTTP request injected by Spring.
   * @param redirectAttributes flash attribute carrier for the success toast.
   * @return redirect view to the referring page, or to {@code /} when it is not a same-origin path.
   */
  @NotNull
  private RedirectView applyActiveOrgUnitSelection(
      @Nullable UUID orgUnitId,
      @Nullable String referer,
      @NotNull HttpServletRequest request,
      RedirectAttributes redirectAttributes) {
    HttpSession session = request.getSession(true);
    if (orgUnitId == null) {
      session.removeAttribute(ACTIVE_ORG_UNIT_SESSION_KEY);
      redirectAttributes.addFlashAttribute("toastSuccess", "orgUnit.switcher.cleared");
    } else {
      session.setAttribute(ACTIVE_ORG_UNIT_SESSION_KEY, orgUnitId.toString());
      redirectAttributes.addFlashAttribute("toastSuccess", "orgUnit.switcher.activated");
    }
    return new RedirectView(safeRedirectTarget(referer));
  }
}
