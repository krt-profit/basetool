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

import de.greluc.krt.profit.basetool.frontend.model.dto.RegistrationStatusDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Renders the "waiting for admin approval" page (epic #720, Track 1). A brand-new Discord user
 * lands here — they are authenticated but their only authority is {@code ROLE_PENDING_APPROVAL}, so
 * {@link de.greluc.krt.profit.basetool.frontend.config.BackendRoleSyncFilter} redirects every other
 * request here until an admin approves them. The page itself is exempt from that redirect (else it
 * would loop), as is the {@link #status()} poll it drives.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class PendingApprovalPageController {

  /** Backend endpoint returning the caller's own approval status. */
  private static final String REGISTRATION_STATUS_URI = "/api/v1/users/me/registration-status";

  private final BackendApiClient backendApiClient;

  /**
   * Renders the waiting-for-approval page.
   *
   * @return the {@code pending-approval} view name
   */
  @GetMapping("/pending-approval")
  public String pendingApproval() {
    return "pending-approval";
  }

  /**
   * Backs the waiting page's status poll (REQ-SEC-017): returns the caller's live approval status
   * so the page can send them into the tool the moment an admin approves, instead of leaving them
   * to discover it by logging out and back in.
   *
   * <p>A backend failure is reported as an unknown status rather than an error — the page simply
   * keeps polling. A dead token is deliberately NOT swallowed: {@code
   * ReauthenticationRequiredException} propagates to {@code GlobalExceptionHandler}, which answers
   * the poll with the {@code 401} + {@code X-Reauthenticate} contract (REQ-SEC-012) so the browser
   * re-authenticates instead of polling a session that can no longer reach the backend.
   *
   * @return the caller's approval status, or a {@code null} status when the backend could not be
   *     read
   */
  @GetMapping("/pending-approval/status")
  @ResponseBody
  @NotNull
  public RegistrationStatusDto status() {
    try {
      RegistrationStatusDto dto =
          backendApiClient.get(REGISTRATION_STATUS_URI, RegistrationStatusDto.class);
      return dto == null ? new RegistrationStatusDto(null) : dto;
    } catch (BackendServiceException e) {
      // Already logged once at the BackendApiClient boundary (REQ-OBS-001); this poll repeats every
      // few seconds, so re-logging it here would turn one backend outage into a log storm.
      log.debug("Approval status poll could not read the backend; reporting an unknown status.", e);
      return new RegistrationStatusDto(null);
    }
  }
}
