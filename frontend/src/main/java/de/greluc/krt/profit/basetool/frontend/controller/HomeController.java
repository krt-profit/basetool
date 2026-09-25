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
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller for {@code /}: the landing page for anonymous visitors and the dashboard (upcoming
 * missions, announcement, once-per-session welcome toast) for members.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@Slf4j
public class HomeController {

  /** Response type for the paged {@code /missions/search} upcoming-mission listing. */
  private static final ParameterizedTypeReference<PageResponse<MissionListDto>> MISSION_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Response type for the {@code /users/me/org-unit-ids} direct-membership id list. */
  private static final ParameterizedTypeReference<List<UUID>> UUID_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Response type for the {@code /announcement} public-announcement map. */
  private static final ParameterizedTypeReference<Map<String, Object>> ANNOUNCEMENT_MAP_TYPE =
      new ParameterizedTypeReference<>() {};

  @Value("${app.ui.notification-duration:5000}")
  private long notificationDuration;

  private final BackendApiClient backendApiClient;

  /**
   * Renders {@code /}. For an anonymous visitor it returns the landing page without any backend
   * call, model attribute or session (REQ-SEC-052); for a member it renders the dashboard.
   *
   * @param model Thymeleaf model populated with mission, announcement, username and toast flags
   * @param principal authenticated OIDC user, or {@code null} for an anonymous visitor
   * @param request the current request; the session is taken from it only for a member
   * @return {@code landing} for an anonymous visitor, {@code index} for a member
   */
  @NotNull
  @GetMapping("/")
  public String home(
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      jakarta.servlet.http.HttpServletRequest request) {
    if (principal == null) {
      return "landing";
    }
    try {
      Instant now = Instant.now();
      Instant horizon = now.plus(7, ChronoUnit.DAYS);
      String searchUri =
          "/api/v1/missions/search?start="
              + now
              + "&end="
              + horizon
              + "&sort=plannedStartTime,asc&status=PLANNED&status=ACTIVE&size=50";
      PageResponse<MissionListDto> upcomingPage =
          backendApiClient.get(searchUri, MISSION_PAGE_TYPE);
      List<MissionListDto> upcomingMissions =
          (upcomingPage != null && upcomingPage.content() != null)
              ? upcomingPage.content()
              : List.of();
      model.addAttribute("upcomingMissions", upcomingMissions);
    } catch (BackendServiceException e) {
      log.debug("Could not fetch upcoming missions", e);
      model.addAttribute("upcomingMissions", List.of());
      model.addAttribute("error", "error.mission.fetch");
    } catch (Exception e) {
      log.error("Could not fetch upcoming missions", e);
      model.addAttribute("upcomingMissions", List.of());
      model.addAttribute("error", "error.mission.fetch");
    }

    model.addAttribute("myOrgUnitIds", Set.of());

    model.addAttribute("username", principal.getPreferredUsername());

    HttpSession session = request.getSession(true);
    if (session.getAttribute("welcomeMessageShown") == null) {
      model.addAttribute("showLoginNotification", true);
      model.addAttribute("notificationDuration", notificationDuration);
      session.setAttribute("welcomeMessageShown", true);
    }

    try {
      de.greluc.krt.profit.basetool.frontend.model.dto.UserDto currentUser =
          backendApiClient.get(
              "/api/v1/users/me", de.greluc.krt.profit.basetool.frontend.model.dto.UserDto.class);
      model.addAttribute("currentUser", currentUser);

      Set<UUID> myOrgUnitIds = new HashSet<>();
      if (currentUser.squadrons() != null) {
        for (SquadronReferenceDto su : currentUser.squadrons()) {
          if (su != null && su.id() != null) {
            myOrgUnitIds.add(su.id());
          }
        }
      }
      if (currentUser.squadron() != null && currentUser.squadron().id() != null) {
        myOrgUnitIds.add(currentUser.squadron().id());
      }
      try {
        List<UUID> directOrgUnitIds =
            backendApiClient.get("/api/v1/users/me/org-unit-ids", UUID_LIST_TYPE);
        if (directOrgUnitIds != null) {
          for (UUID id : directOrgUnitIds) {
            if (id != null) {
              myOrgUnitIds.add(id);
            }
          }
        }
      } catch (Exception ex) {
        log.warn("Could not fetch own org-unit memberships for the home highlight", ex);
      }
      model.addAttribute("myOrgUnitIds", myOrgUnitIds);

      Map<String, Object> announcement =
          backendApiClient.get("/api/v1/announcement", ANNOUNCEMENT_MAP_TYPE);
      model.addAttribute("announcement", announcement);

      boolean unread = false;
      if (announcement != null && announcement.containsKey("id")) {
        String announcementId = (String) announcement.get("id");
        if (currentUser.lastReadAnnouncementId() == null
            || !currentUser.lastReadAnnouncementId().toString().equals(announcementId)) {
          unread = true;
        }
      }
      model.addAttribute("unreadAnnouncement", unread);
    } catch (BackendServiceException e) {
      log.debug("Could not load home user/announcement context", e);
    } catch (Exception e) {
      log.warn("Unexpected failure building home context", e);
    }
    return "index";
  }

  /**
   * Marks the given announcement as read for the current user; backend failures are logged and
   * swallowed.
   *
   * @param id announcement id to mark as read
   * @return redirect back to {@code /}
   */
  @NotNull
  @org.springframework.web.bind.annotation.PostMapping("/announcement/read")
  public String markAnnouncementAsRead(
      @org.springframework.web.bind.annotation.RequestParam String id) {
    try {
      backendApiClient.put("/api/v1/users/me/read-announcement/" + id, null, Void.class);
    } catch (Exception e) {
      log.error("Failed to mark announcement as read", e);
    }
    return "redirect:/";
  }

  /**
   * AJAX variant of {@link #markAnnouncementAsRead}, selected by {@code X-Requested-With:
   * XMLHttpRequest}, so the page removes the control in place (REQ-FE-005).
   *
   * @param id announcement id to mark as read
   * @return {@code 200} on success, {@code 502} on a backend failure
   */
  @org.springframework.web.bind.annotation.PostMapping(
      value = "/announcement/read",
      headers = "X-Requested-With=XMLHttpRequest")
  @org.springframework.web.bind.annotation.ResponseBody
  public org.springframework.http.ResponseEntity<Void> markAnnouncementAsReadAjax(
      @org.springframework.web.bind.annotation.RequestParam String id) {
    try {
      backendApiClient.put("/api/v1/users/me/read-announcement/" + id, null, Void.class);
      return org.springframework.http.ResponseEntity.ok().build();
    } catch (Exception e) {
      log.error("Failed to mark announcement as read", e);
      return org.springframework.http.ResponseEntity.status(502).build();
    }
  }
}
