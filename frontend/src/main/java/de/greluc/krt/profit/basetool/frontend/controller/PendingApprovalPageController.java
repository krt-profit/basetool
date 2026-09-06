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
 * Renders the account-status page that a registration without access is routed to (epic #720, Track
 * 1, REQ-SEC-017). A brand-new Discord user lands here — they are authenticated but their only
 * authority is {@code ROLE_PENDING_APPROVAL}, so {@link BackendRoleSyncFilter} redirects every
 * other request here until an admin decides. The page itself is exempt from that redirect (else it
 * would loop), as is the {@link #status()} poll it drives.
 *
 * <p><b>The filter routes {@code PENDING} and {@code REJECTED} to the same path; this controller
 * splits them again for the render.</b> The two states are deliberately equivalent for access
 * control (both carry no authorities), but they owe the user opposite messages. A {@code REJECTED}
 * registration is terminal — {@code UserRegistrationService} answers a decision on anything but a
 * still-{@code PENDING} row with a {@code 409} — so showing it the waiting copy tells a user whose
 * decision has already been made against them to keep waiting for it. That is how a rejection from
 * 2026-06-29 was still being reported as a stuck approval weeks later, and with {@code
 * app.mail.enabled=false} on production the rejection mail (REQ-NOTIF-014) that would otherwise
 * have closed the loop never went out either, leaving this page as the user's only channel.
 */
@Controller
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
   * Model attribute selecting the role-less copy (REQ-SEC-053) and suppressing the status poll.
   *
   * <p>Its own attribute rather than a third value of {@link #MODEL_REJECTED}, because the three
   * states are not degrees of the same thing: a pending member waits for a decision that has been
   * asked for, a rejected one has been answered, and a role-less one has already been approved and
   * is waiting for a role nobody has been asked to grant. Telling the third to wait for approval
   * points them at an administrator who has already acted.
   */
  static final String MODEL_NO_ROLE = "registrationNoRole";

  private final BackendApiClient backendApiClient;

  /**
   * Renders the account-status page, choosing its copy from the caller's live approval status.
   *
   * <p>{@code ACTIVE} never belongs here: the redirect that owns this path fires only for a
   * non-approved registration, so an approved caller can only have arrived via a stale bookmark or
   * a tab left open across the approval — and is sent to the dashboard rather than shown a waiting
   * page for an approval they already hold. {@code REJECTED} renders the rejection copy and
   * suppresses the poll script. Every other outcome — {@code PENDING}, and an unreadable backend —
   * renders the waiting copy, so a backend outage can never tell a still-pending member they were
   * declined.
   *
   * <p>The {@code ACTIVE} redirect MUST invalidate the session's cached approval verdict first.
   * This page is reached because {@link BackendRoleSyncFilter} believes the caller is not approved,
   * and that belief is served from the session for up to its re-check interval — so redirecting
   * without clearing it makes the two disagree and bounces the browser between {@code /} and here
   * until the cache expires. Neither hop costs a backend read, so the loop runs at full speed into
   * the browser's redirect cap rather than merely being slow.
   *
   * @param model receives {@link #MODEL_REJECTED}
   * @param request supplies the session whose stale verdict the {@code ACTIVE} redirect clears; no
   *     session is created if there is none
   * @return the {@code pending-approval} view name, or a redirect to the dashboard when the caller
   *     turns out to be approved
   */
  @GetMapping("/pending-approval")
  @NotNull
  public String pendingApproval(@NotNull Model model, @NotNull HttpServletRequest request) {
    String approvalStatus = readApprovalStatus();
    if (STATE_ACTIVE.equals(approvalStatus)) {
      BackendRoleSyncFilter.forgetApprovalVerdict(request.getSession(false));
      return "redirect:/";
    }
    // The role-less verdict never comes from approvalStatus — the backend does not send it there
    // (REQ-SEC-053). It is derived from the 403 the role sync met, and BackendRoleSyncFilter caches
    // it in the same session attribute the approval verdict uses, which is what routed the caller
    // here in the first place.
    boolean noRole = BackendRoleSyncFilter.isRoleLess(request.getSession(false));
    model.addAttribute(MODEL_REJECTED, STATE_REJECTED.equals(approvalStatus));
    model.addAttribute(MODEL_NO_ROLE, noRole && !STATE_REJECTED.equals(approvalStatus));
    return "pending-approval";
  }

  /**
   * Backs the waiting page's status poll (REQ-SEC-017): returns the caller's live approval status
   * so the page can send them into the tool the moment an admin approves, instead of leaving them
   * to discover it by logging out and back in — and so a decision that lands while the page is open
   * replaces the waiting copy in place rather than leaving stale text on screen.
   *
   * <p>A backend failure is reported as an unknown status rather than an error, so the page simply
   * keeps polling.
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
   * Reads the caller's own approval status from the backend, degrading a backend failure to an
   * unknown status. Shared by the render and the poll so the two can never disagree about what an
   * unreadable backend means.
   *
   * <p>A dead token is deliberately NOT swallowed: {@code ReauthenticationRequiredException}
   * propagates to {@code GlobalExceptionHandler}, which answers the poll with the {@code 401} +
   * {@code X-Reauthenticate} contract and the page render with a redirect into the Keycloak login
   * flow (REQ-SEC-012), so the browser re-authenticates instead of polling a session that can no
   * longer reach the backend.
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
      // Already logged once at the BackendApiClient boundary (REQ-OBS-001); the poll repeats every
      // few seconds, so re-logging it here would turn one backend outage into a log storm.
      log.debug("Approval status could not be read; reporting an unknown status.", e);
      return null;
    }
  }
}
