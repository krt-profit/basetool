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
 * Owner of the per-user "active OrgUnit" preference (admin and non-admin alike). The state lives in
 * the frontend's Redis-backed Spring Session — NOT in the backend — because backend REST calls
 * relay only the OAuth2 bearer token and no session cookies, so a backend-side {@code
 * HttpSession.setAttribute} would be lost as soon as the response returned. The active OrgUnit is
 * propagated to the backend on every API call via the {@code X-Active-Org-Unit-Id} request header
 * (see {@code ActiveSquadronRelayFilter} on the frontend and {@link
 * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#ACTIVE_ORG_UNIT_HEADER} on the
 * backend); the backend honours the pinned id only when it matches one of the caller's actual
 * memberships (non-admin path) or is set by an admin (admin path).
 *
 * <p>Class-level gate is {@code isAuthenticated()} since R5.e widened the switcher to every
 * authenticated user with &gt;1 membership. The backend independently re-validates the pin against
 * the caller's actual memberships, so a spoofed POST cannot make a single-Staffel user appear to
 * pin an OrgUnit they do not belong to.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/me")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MeFrontendController {

  /**
   * HTTP session attribute name under which the caller's currently selected OrgUnit lives. Mirrored
   * on the backend's {@link de.greluc.krt.profit.basetool.backend.service.OwnerScopeService} via
   * the {@code X-Active-Org-Unit-Id} request header set by the {@code ActiveSquadronRelayFilter};
   * the actual storage is the frontend's Spring Session (Redis-backed in dev/prod), NOT the
   * backend's request-scoped session.
   */
  public static final String ACTIVE_ORG_UNIT_SESSION_KEY = "iridium.activeOrgUnitId";

  /**
   * Sets or clears the caller's active OrgUnit selection in the frontend session. {@code orgUnitId}
   * blank/empty clears the selection (admin returns to "all OrgUnits" mode; non-admin returns to
   * "union of memberships"); a non-blank UUID activates that OrgUnit. The redirect makes the next
   * page render see the new context; the backend learns about the change on the next API call
   * through the {@code X-Active-Org-Unit-Id} header.
   *
   * <p>The frontend does not validate that the picked OrgUnit is actually one of the caller's
   * memberships — the backend's {@link
   * de.greluc.krt.profit.basetool.backend.service.OwnerScopeService#currentScopePredicate()}
   * re-validates the pin against the membership union and silently falls back to the union read if
   * the pin is foreign. Defence in depth against a spoofed POST.
   *
   * @param orgUnitId the OrgUnit to activate, blank/null to clear; never silently rejected.
   * @param referer optional referer field used to redirect back; defaults to {@code /} when
   *     missing.
   * @param request HTTP request injected by Spring; never {@code null}.
   * @param redirectAttributes flash attribute carrier for the success toast.
   * @return redirect view to the referring page so the next render sees the new context.
   */
  @NotNull
  @PostMapping("/active-org-unit")
  public RedirectView setActiveOrgUnit(
      @RequestParam(value = "orgUnitId", required = false) @Nullable String orgUnitId,
      @RequestParam(value = "_referer", required = false) @Nullable String referer,
      HttpServletRequest request,
      RedirectAttributes redirectAttributes) {
    return applyActiveOrgUnitSelection(orgUnitId, referer, request, redirectAttributes);
  }

  /**
   * Implementation backing {@link #setActiveOrgUnit}. Writes the chosen OrgUnit id to {@link
   * #ACTIVE_ORG_UNIT_SESSION_KEY} on the frontend session (or clears it on blank input) and emits
   * the {@code orgUnit.switcher.activated} / {@code orgUnit.switcher.cleared} flash toast.
   *
   * @param rawOrgUnitId the OrgUnit id to activate, blank/null to clear.
   * @param referer optional referer field used to redirect back.
   * @param request HTTP request injected by Spring.
   * @param redirectAttributes flash attribute carrier for the success toast.
   * @return redirect view to the referring page.
   */
  @NotNull
  private RedirectView applyActiveOrgUnitSelection(
      @Nullable String rawOrgUnitId,
      @Nullable String referer,
      @NotNull HttpServletRequest request,
      RedirectAttributes redirectAttributes) {
    HttpSession session = request.getSession(true);
    if (rawOrgUnitId == null || rawOrgUnitId.isBlank()) {
      session.removeAttribute(ACTIVE_ORG_UNIT_SESSION_KEY);
      redirectAttributes.addFlashAttribute("toastSuccess", "orgUnit.switcher.cleared");
    } else {
      // Store as the UUID's canonical string form so the Redis-backed Spring Session can
      // round-trip the value without serializer ambiguity. Spring Session's default
      // JdkSerializationRedisSerializer plus the JSON wrapper in some configurations can
      // change a UUID instance into a String on deserialization — storing the String
      // representation up front avoids that brittleness and matches how the readers parse
      // it back.
      UUID parsed = UUID.fromString(rawOrgUnitId.trim());
      session.setAttribute(ACTIVE_ORG_UNIT_SESSION_KEY, parsed.toString());
      redirectAttributes.addFlashAttribute("toastSuccess", "orgUnit.switcher.activated");
    }
    return new RedirectView(referer != null && !referer.isBlank() ? referer : "/");
  }
}
