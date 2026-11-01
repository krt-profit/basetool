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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import de.greluc.krt.profit.basetool.frontend.dashboard.client.DashboardBackendClient;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardMode;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Controller for {@code /}: the landing page for anonymous visitors and the widget dashboard
 * (REQ-UI-034, once-per-session welcome toast) for members, plus the announcement read marker.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
public class HomeController {

  @Value("${app.ui.notification-duration:5000}")
  private long notificationDuration;

  /** Reads the dashboard's backend data. */
  private final DashboardBackendClient dashboardClient;

  /** Reads the member's dashboard layout. */
  private final DashboardReader dashboardReader;

  /**
   * Renders {@code /}. For an anonymous visitor it returns the landing page without any backend
   * call, model attribute or session (REQ-SEC-052); for a member it renders the dashboard board,
   * whose widgets load themselves (REQ-UI-034).
   *
   * @param model Thymeleaf model populated with the dashboard, username and toast flags
   * @param principal authenticated OIDC user, or {@code null} for an anonymous visitor
   * @param request the current request; the session is taken from it only for a member
   * @return {@code landing} for an anonymous visitor, {@code index} for a member
   */
  @NotNull
  @GetMapping("/")
  public String home(
      Model model, @AuthenticationPrincipal OidcUser principal, HttpServletRequest request) {
    if (principal == null) {
      return "dashboard/landing";
    }
    model.addAttribute("username", principal.getPreferredUsername());
    DashboardContext context = DashboardContext.of(model);
    model.addAttribute(
        "dashboard",
        dashboardReader.read(
            DashboardMode.MEMBER, context.access(), context.activeUnitId(), context.units()));

    HttpSession session = request.getSession(true);
    if (session.getAttribute("welcomeMessageShown") == null) {
      model.addAttribute("showLoginNotification", true);
      model.addAttribute("notificationDuration", notificationDuration);
      session.setAttribute("welcomeMessageShown", true);
    }

    SectionFallback.run(
        log,
        "home-context",
        () -> model.addAttribute("currentUser", dashboardClient.currentUser()));
    return "dashboard/index";
  }

  /**
   * Marks the given announcement as read for the current user; backend failures are logged and
   * swallowed.
   *
   * @param id announcement id to mark as read
   * @return redirect back to {@code /}
   */
  @NotNull
  @PostMapping("/announcement/read")
  @PreAuthorize("isAuthenticated()")
  public String markAnnouncementAsRead(@RequestParam UUID id) {
    SectionFallback.run(log, "announcement-read", () -> dashboardClient.markAnnouncementRead(id));
    return "redirect:/";
  }

  /**
   * AJAX variant of {@link #markAnnouncementAsRead}, selected by {@code X-Requested-With:
   * XMLHttpRequest}, so the page removes the control in place (REQ-FE-005).
   *
   * @param id announcement id to mark as read
   * @return {@code 200} on success; a failure is answered by the global handler with the backend's
   *     status
   */
  @PostMapping(value = "/announcement/read", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Void> markAnnouncementAsReadAjax(@RequestParam UUID id) {
    dashboardClient.markAnnouncementRead(id);
    return ResponseEntity.ok().build();
  }
}
