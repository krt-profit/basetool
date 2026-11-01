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
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.model.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionListDto;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the bodies of the dashboard widgets (REQ-UI-034): each widget is its own request, so a
 * failing one fails alone. A backend failure is not swallowed here; the central error mapping
 * answers it and the page shows that widget's error state.
 */
@Slf4j
@Controller
@UsesLayoutModel
@RequestMapping("/dashboard/widgets")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DashboardWidgetController {

  private static final String WIDGETS = "dashboard/fragments/widgets :: ";

  private final DashboardBackendClient client;

  private final FrontendAuthHelperService authHelper;

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
  @GetMapping(value = "/upcoming-days", headers = "X-Requested-With=XMLHttpRequest")
  public String upcomingDays(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable UUID unit,
      @RequestParam(required = false) @Nullable Integer days,
      @RequestParam(required = false) @Nullable Integer rows) {
    int horizonDays = DashboardWidgetOptions.days(days);
    int limit = DashboardWidgetOptions.rows(rows);
    List<MissionListDto> missions = loadUpcoming(horizonDays, unit);
    model.addAttribute("upcomingTotal", missions.size());
    model.addAttribute("upcomingMissions", missions.stream().limit(limit).toList());
    model.addAttribute("upcomingDays", horizonDays);
    model.addAttribute(
        "myOrgUnitIds", SectionFallback.load(log, "own-org-units", this::ownUnits, Set.of()));
    return WIDGETS + "upcomingDays";
  }

  /**
   * Renders the announcement, with the inline editor for an administrator.
   *
   * @param model the Thymeleaf model
   * @return the {@code information} fragment
   */
  @NotNull
  @GetMapping(value = "/information", headers = "X-Requested-With=XMLHttpRequest")
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
  @GetMapping(value = "/notifications", headers = "X-Requested-With=XMLHttpRequest")
  public String notifications(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable Integer rows,
      @RequestParam(required = false) @Nullable DashboardStatus status) {
    model.addAttribute("notificationRows", DashboardWidgetOptions.rows(rows));
    model.addAttribute("notificationStatus", DashboardWidgetOptions.status(status));
    return WIDGETS + "notifications";
  }

  /**
   * Renders the member's favourites; the navigation advice puts them into the model.
   *
   * @return the {@code favourites} fragment
   */
  @NotNull
  @GetMapping(value = "/favourites", headers = "X-Requested-With=XMLHttpRequest")
  public String favourites() {
    return WIDGETS + "favourites";
  }

  @NotNull
  private List<MissionListDto> loadUpcoming(int days, @Nullable UUID unit) {
    Instant now = Instant.now();
    PageResponse<MissionListDto> page =
        client.upcomingMissions(now, now.plus(days, ChronoUnit.DAYS));
    if (page == null || page.content() == null) {
      return List.of();
    }
    return page.content().stream()
        .filter(mission -> unit == null || ownedBy(mission, unit))
        .toList();
  }

  private static boolean ownedBy(@NotNull MissionListDto mission, @NotNull UUID unit) {
    return mission.owningSquadron() != null && unit.equals(mission.owningSquadron().id());
  }

  private boolean isUnread(@Nullable AnnouncementDto announcement) {
    if (announcement == null || announcement.id() == null) {
      return false;
    }
    UserDto currentUser = SectionFallback.load(log, "current-user", client::currentUser, null);
    return currentUser == null || !announcement.id().equals(currentUser.lastReadAnnouncementId());
  }

  @NotNull
  private Set<UUID> ownUnits() {
    Set<UUID> ids = new HashSet<>();
    UserDto currentUser = client.currentUser();
    if (currentUser != null) {
      if (currentUser.squadrons() != null) {
        for (SquadronReferenceDto squadron : currentUser.squadrons()) {
          if (squadron != null && squadron.id() != null) {
            ids.add(squadron.id());
          }
        }
      }
      if (currentUser.squadron() != null && currentUser.squadron().id() != null) {
        ids.add(currentUser.squadron().id());
      }
    }
    List<UUID> direct = client.myOrgUnitIds();
    if (direct != null) {
      direct.stream().filter(java.util.Objects::nonNull).forEach(ids::add);
    }
    return ids;
  }
}
