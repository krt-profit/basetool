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
import de.greluc.krt.profit.basetool.frontend.dashboard.model.AnnouncementDto;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardStatus;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import de.greluc.krt.profit.basetool.frontend.hangar.model.ShipReadinessDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.security.CurrentUser;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.kernel.web.FragmentController;
import de.greluc.krt.profit.basetool.frontend.kernel.web.FragmentGetMapping;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionListDto;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the bodies of the dashboard widgets (REQ-UI-034): each widget is its own request, so a
 * failing one fails alone. A backend failure is not swallowed here; the central error mapping
 * answers it and the page shows that widget's error state.
 */
@Slf4j
@Controller
@FragmentController("/dashboard/widgets")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DashboardWidgetController {

  private static final String WIDGETS = "dashboard/fragments/widgets :: ";

  private final DashboardBackendClient client;

  private final FrontendAuthHelperService authHelper;

  private final OwnOrgUnits ownOrgUnits;

  private final DashboardWidgetOptions options;

  /**
   * Renders the missions of the coming days.
   *
   * @param model the Thymeleaf model
   * @param unit the pinned org unit, or {@code null} to follow the active context
   * @param days the days ahead
   * @param rows the rows shown
   * @return the {@code upcomingDays} fragment
   */
  @NotNull
  @FragmentGetMapping("/upcoming-days")
  public String upcomingDays(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable UUID unit,
      @RequestParam(required = false) @Nullable Integer days,
      @RequestParam(required = false) @Nullable Integer rows) {
    int horizonDays = options.days(DashboardWidgetType.UPCOMING_DAYS, days);
    int limit = options.rows(DashboardWidgetType.UPCOMING_DAYS, rows);
    List<MissionListDto> missions = loadUpcoming(horizonDays, unit);
    model.addAttribute("upcomingTotal", missions.size());
    model.addAttribute("upcomingMissions", missions.stream().limit(limit).toList());
    model.addAttribute("upcomingDays", horizonDays);
    model.addAttribute(
        "myOrgUnitIds", SectionFallback.load(log, "own-org-units", ownOrgUnits::ids, Set.of()));
    return WIDGETS + "upcomingDays";
  }

  /**
   * Renders the announcement, with the inline editor for an administrator.
   *
   * @param model the Thymeleaf model
   * @return the {@code information} fragment
   */
  @NotNull
  @FragmentGetMapping("/information")
  public String information(@NotNull Model model) {
    AnnouncementDto announcement = client.announcement();
    model.addAttribute("announcement", announcement);
    model.addAttribute("unreadAnnouncement", isUnread(announcement));
    if (authHelper.isAdmin()) {
      model.addAttribute("adminAnnouncement", client.adminAnnouncement());
    }
    return WIDGETS + "information";
  }

  /**
   * Renders the notifications widget's shell; the script fills it from the bell's endpoint.
   *
   * @param model the Thymeleaf model
   * @param rows the rows shown
   * @param status the status filter
   * @return the {@code notifications} fragment
   */
  @NotNull
  @FragmentGetMapping("/notifications")
  public String notifications(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable Integer rows,
      @RequestParam(required = false) @Nullable DashboardStatus status) {
    model.addAttribute("notificationRows", options.rows(DashboardWidgetType.NOTIFICATIONS, rows));
    model.addAttribute(
        "notificationStatus", options.status(DashboardWidgetType.NOTIFICATIONS, status));
    return WIDGETS + "notifications";
  }

  /**
   * Renders the member's favourites; the navigation advice puts them into the model.
   *
   * @return the {@code favourites} fragment
   */
  @NotNull
  @FragmentGetMapping("/favourites")
  public String favourites() {
    return WIDGETS + "favourites";
  }

  /**
   * Renders the member's next missions: those of the member's units and those the member is signed
   * up for.
   *
   * @param model the Thymeleaf model
   * @param rows the rows shown
   * @return the {@code nextMission} fragment
   */
  @NotNull
  @FragmentGetMapping("/next-mission")
  public String nextMission(
      @NotNull Model model, @RequestParam(required = false) @Nullable Integer rows) {
    model.addAttribute(
        "nextMissions", client.nextMissions(options.rows(DashboardWidgetType.NEXT_MISSION, rows)));
    model.addAttribute(
        "myOrgUnitIds", SectionFallback.load(log, "own-org-units", ownOrgUnits::ids, Set.of()));
    return WIDGETS + "nextMission";
  }

  /**
   * Renders how many of the member's ships are fitted, with the member's own hangar room for the
   * live update.
   *
   * @param model the Thymeleaf model
   * @param principal the signed-in member, whose id names the room
   * @return the {@code hangarReadiness} fragment
   */
  @NotNull
  @FragmentGetMapping("/hangar-readiness")
  public String hangarReadiness(
      @NotNull Model model, @AuthenticationPrincipal @Nullable OidcUser principal) {
    ShipReadinessDto readiness = client.hangarReadiness();
    model.addAttribute("hangarTotal", readiness == null ? 0L : readiness.total());
    model.addAttribute("hangarFitted", readiness == null ? 0L : readiness.fitted());
    String id = CurrentUser.userIdText(principal);
    model.addAttribute(
        "hangarLiveSyncTopic", id == null ? null : LiveSyncTopicClass.HANGAR_OWN.topic(id));
    return WIDGETS + "hangarReadiness";
  }

  @NotNull
  private List<MissionListDto> loadUpcoming(int days, @Nullable UUID unit) {
    Instant now = Instant.now();
    PageResponse<MissionListDto> page =
        client.upcomingMissions(now, now.plus(days, ChronoUnit.DAYS), unit);
    return PageResponse.contentOf(page);
  }

  private boolean isUnread(@Nullable AnnouncementDto announcement) {
    if (announcement == null || announcement.id() == null) {
      return false;
    }
    UserDto currentUser = SectionFallback.load(log, "current-user", client::currentUser, null);
    return currentUser == null || !announcement.id().equals(currentUser.lastReadAnnouncementId());
  }
}
