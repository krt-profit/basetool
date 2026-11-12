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

package de.greluc.krt.profit.basetool.frontend.orgunit.web;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.web.ActiveOrgUnitRestore;
import de.greluc.krt.profit.basetool.frontend.kernel.web.ActiveSquadronContext;
import de.greluc.krt.profit.basetool.frontend.orgunit.client.OrgUnitBackendClient;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.ActiveOrgUnitChoiceResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Switches the caller's active org unit: stores the choice in the backend (REQ-ORG-031), mirrors it
 * in the frontend session and relays it to the backend as the {@code X-Active-Org-Unit-Id} header.
 * The backend re-validates the pin on every request.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/me")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class MeFrontendController {

  /** Flash key of the success toast the toast fragment renders. */
  static final String SUCCESS_TOAST = "successToast";

  /** Flash key of the error toast the toast fragment renders. */
  static final String ERROR_TOAST = "errorToast";

  /** The org-unit domain's backend client. */
  private final OrgUnitBackendClient orgUnitBackendClient;

  /**
   * Sets the caller's active org unit, or clears it for a blank id, and redirects back.
   *
   * <p>A unit the backend refuses leaves the session unchanged. When the backend cannot store the
   * choice, the session still switches and the toast says the choice lasts for this session only.
   *
   * @param orgUnitId the org unit to activate, {@code null} (absent or blank) for all of the
   *     caller's org units.
   * @param referer optional redirect target; anything but a same-origin path falls back to {@code
   *     /}.
   * @param request HTTP request injected by Spring; never {@code null}.
   * @param redirectAttributes flash attribute carrier for the toast.
   * @return redirect view to the referring page so the next render sees the new context.
   */
  @NotNull
  @PostMapping("/active-org-unit")
  public RedirectView setActiveOrgUnit(
      @RequestParam(value = "orgUnitId", required = false) @Nullable UUID orgUnitId,
      @RequestParam(value = "_referer", required = false) @Nullable String referer,
      HttpServletRequest request,
      RedirectAttributes redirectAttributes) {
    HttpSession session = request.getSession(true);
    try {
      ActiveOrgUnitChoiceResponse stored = orgUnitBackendClient.storeActiveOrgUnitChoice(orgUnitId);
      applyPin(session, stored != null ? stored.orgUnitId() : orgUnitId);
      redirectAttributes.addFlashAttribute(SUCCESS_TOAST, switchedToastKey(orgUnitId));
    } catch (BackendServiceException e) {
      if (e.getStatusCode() == HttpStatus.BAD_REQUEST.value()) {
        log.debug("The backend refused the org-unit choice", e);
        redirectAttributes.addFlashAttribute(ERROR_TOAST, "orgUnit.switcher.rejected");
        return new RedirectView(safeRedirectTarget(referer));
      }
      log.warn("Storing the org-unit choice failed; it applies to this session only", e);
      applyPin(session, orgUnitId);
      redirectAttributes.addFlashAttribute(ERROR_TOAST, "orgUnit.switcher.notStored");
    }
    return new RedirectView(safeRedirectTarget(referer));
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
   * Names the message of the toast after a stored switch.
   *
   * @param orgUnitId the activated org unit, {@code null} for all of the caller's org units
   * @return the message key of the toast
   */
  @Contract(pure = true)
  private static @NotNull String switchedToastKey(@Nullable UUID orgUnitId) {
    return orgUnitId == null ? "orgUnit.switcher.cleared" : "orgUnit.switcher.activated";
  }

  /**
   * Stores or clears the session pin and records that it reflects the caller's own choice, so the
   * session's restore does not replace it.
   *
   * @param session the caller's session; never {@code null}
   * @param orgUnitId the org unit to pin, {@code null} for all of the caller's org units
   */
  private static void applyPin(@NotNull HttpSession session, @Nullable UUID orgUnitId) {
    if (orgUnitId == null) {
      session.removeAttribute(ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY);
    } else {
      session.setAttribute(ActiveSquadronContext.ACTIVE_ORG_UNIT_SESSION_KEY, orgUnitId.toString());
    }
    String principal = ActiveOrgUnitRestore.currentPrincipal();
    if (principal != null) {
      ActiveOrgUnitRestore.markRestored(session, principal);
    }
  }
}
