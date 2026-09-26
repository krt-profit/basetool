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

import de.greluc.krt.profit.basetool.frontend.config.BackendRoleSyncFilter;
import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.model.dto.RegistrationStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Renders the account-status page to which {@link BackendRoleSyncFilter} routes a registration
 * without access (REQ-SEC-017). The page and its {@link #status()} poll are exempt from that
 * redirect. Pending, rejected and role-less registrations each get their own copy.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class PendingApprovalPageController {

  /** Backend endpoint returning the caller's own approval status. */
  private static final String REGISTRATION_STATUS_URI = "/api/v1/users/me/registration-status";

  /** Approved registration — nothing to wait for, so this page is not the right surface. */
  private static final String STATE_ACTIVE = "ACTIVE";

  /**
   * Registration declined by an admin; terminal, and the one state the waiting copy misdescribes.
   */
  private static final String STATE_REJECTED = "REJECTED";

  /**
   * Model attribute selecting the rejection copy over the waiting copy and suppressing the status
   * poll. Always set (never {@code null}), so the template may negate it.
   */
  static final String MODEL_REJECTED = "registrationRejected";

  /**
   * Model attribute selecting the copy for an approved account without a role (REQ-SEC-053) and
   * suppressing the status poll.
   */
  static final String MODEL_NO_ROLE = "registrationNoRole";

  private final BackendApiClient backendApiClient;

  /**
   * Renders the account-status page, choosing its copy from the caller's live approval status.
   *
   * <p>{@code REJECTED} renders the rejection copy without the poll; a role-less caller renders the
   * role-less copy; any other {@code ACTIVE} caller is redirected to the dashboard after the
   * session's cached approval verdict is cleared. {@code PENDING} and an unreadable backend render
   * the waiting copy.
   *
   * @param model receives {@link #MODEL_REJECTED}
   * @param request supplies the session whose cached verdict the {@code ACTIVE} redirect clears; no
   *     session is created
   * @return the {@code pending-approval} view name, or a redirect to the dashboard when the caller
   *     is approved
   */
  @GetMapping("/pending-approval")
  @NotNull
  public String pendingApproval(@NotNull Model model, @NotNull HttpServletRequest request) {
    String approvalStatus = readApprovalStatus();
    boolean noRole = BackendRoleSyncFilter.isRoleLess(request.getSession(false));
    if (STATE_ACTIVE.equals(approvalStatus) && !noRole) {
      BackendRoleSyncFilter.forgetApprovalVerdict(request.getSession(false));
      return "redirect:/";
    }
    model.addAttribute(MODEL_REJECTED, STATE_REJECTED.equals(approvalStatus));
    model.addAttribute(MODEL_NO_ROLE, noRole && !STATE_REJECTED.equals(approvalStatus));
    return "pending-approval";
  }

  /**
   * Returns the caller's live approval status for the waiting page's poll (REQ-SEC-017); a backend
   * failure yields an unknown status so the page keeps polling.
   *
   * @return the caller's approval status, or a {@code null} status when the backend could not be
   *     read
   */
  @GetMapping("/pending-approval/status")
  @ResponseBody
  @NotNull
  public RegistrationStatusDto status() {
    return new RegistrationStatusDto(readApprovalStatus());
  }

  /**
   * Reads the caller's approval status from the backend, mapping a backend failure to {@code null}.
   * A {@code ReauthenticationRequiredException} propagates so the browser re-authenticates
   * (REQ-SEC-012).
   *
   * @return {@code PENDING} / {@code ACTIVE} / {@code REJECTED}, or {@code null} when the backend
   *     could not be read
   */
  @Nullable
  private String readApprovalStatus() {
    try {
      RegistrationStatusDto dto =
          backendApiClient.get(REGISTRATION_STATUS_URI, RegistrationStatusDto.class);
      return dto == null ? null : dto.approvalStatus();
    } catch (BackendServiceException e) {
      log.debug("Approval status could not be read; reporting an unknown status.", e);
      return null;
    }
  }
}
