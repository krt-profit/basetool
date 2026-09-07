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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Spring MVC controller for the home page.
 *
 * <p>Renders {@code /} for both guests and authenticated users. The page shows the missions whose
 * planned start falls within the next seven days as a tile grid (a guest-visible search, nearest
 * start first) plus, for authenticated users, the current user record and the active announcement
 * with an unread flag. The first authenticated render in a session also surfaces a transient
 * login-notification toast (session attribute {@code welcomeMessageShown} prevents the toast from
 * re-appearing on every refresh).
 */
@Controller
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
   * Renders {@code /} — the landing page for a visitor with no session, the dashboard for a member.
   *
   * <p><strong>The anonymous branch returns before anything happens.</strong> No backend call, no
   * model attribute, no session. Both matter and both used to be violated on every single hit:
   *
   * <ul>
   *   <li>The seven-day mission grid was fetched and rendered for anyone who asked — name, unit,
   *       status, meeting point, times. That was the largest anonymous read the tool had, and
   *       REQ-SEC-052 closes it.
   *   <li>A session was minted <em>twice over</em>. This method declared an {@link HttpSession}
   *       parameter, which Spring resolves with {@code getSession(true)} whether or not the body
   *       uses it; and {@code SafeCsrfAdvice} forced the deferred CSRF token on every render, which
   *       the default {@code HttpSessionCsrfTokenRepository} saves into a fresh session before any
   *       template runs. Every crawler hit cost two sessions in Redis. The parameter is gone — the
   *       session is now taken explicitly, inside the authenticated branch — and the advice returns
   *       null for an unauthenticated request.
   * </ul>
   *
   * <p>For a member it is unchanged: the upcoming missions, the {@code /me} record, the
   * announcement with its unread flag, and the once-per-session welcome toast.
   *
   * @param model Thymeleaf model populated with mission, announcement, username and toast flags
   * @param principal authenticated OIDC user, or {@code null} for an anonymous visitor
   * @param request the current request; the session is taken from it only for a member
   * @return {@code landing} for an anonymous visitor, {@code index} for a member
   */
  @GetMapping("/")
  public String home(
      Model model,
      @AuthenticationPrincipal OidcUser principal,
      jakarta.servlet.http.HttpServletRequest request) {
    if (principal == null) {
      return "landing";
    }
    // Fetch the missions starting within the next seven days, nearest planned start first
    // (REQ-MISSION-012). Replaces the former single "next mission" banner with a tile grid. Uses
    // the broad mission-list scope (the viewer's own org units PLUS every unit's organisation-wide
    // missions) via /api/v1/missions/search. The first tile is the soonest upcoming mission.
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
      // REQ-OBS-001: the BackendApiClient boundary already logged this (4xx WARN / 5xx ERROR /
      // circuit-open DEBUG). This is the first page after a login, so a routine backend restart
      // must not re-log at ERROR on every load and trip LogbackErrorSpike.
      log.debug("Could not fetch upcoming missions", e);
      model.addAttribute("upcomingMissions", List.of());
      model.addAttribute("error", "error.mission.fetch");
    } catch (Exception e) {
      log.error("Could not fetch upcoming missions", e);
      model.addAttribute("upcomingMissions", List.of());
      model.addAttribute("error", "error.mission.fetch");
    }

    // Default to no own-unit ids so the tile grid can flag the viewer's own-unit missions with a
    // "Meine Einheit" chip (REQ-MISSION-012); populated below for authenticated users.
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

      // Collect the viewer's own org-unit ids so the tile grid can flag own-unit missions with a
      // "Meine Einheit" chip (REQ-MISSION-012). These are the caller's DIRECT memberships across
      // every kind (Staffel / SK / Bereich / OL), with no leadership cascade. The
      // /me/org-unit-ids endpoint is the authoritative kind-agnostic source; the Staffel ids from
      // the already-fetched /me record are unioned in as a fallback so a transient failure of
      // that
      // call still flags own-Staffel missions.
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

      // Fetch Public Announcement
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
      // Fail-soft: the tiles/announcement are optional. The boundary already logged the backend
      // error; keep a DEBUG breadcrumb (correlationId is in the MDC) rather than swallowing it.
      log.debug("Could not load home user/announcement context", e);
    } catch (Exception e) {
      log.warn("Unexpected failure building home context", e);
    }
    return "index";
  }

  /**
   * Marks the given announcement as read for the current user by delegating to {@code PUT
   * /api/v1/users/me/read-announcement/{id}}. Backend failures are logged but swallowed — a failed
   * mark-as-read must not block navigation back to the home page.
   *
   * @param id announcement id to mark as read
   * @return redirect back to {@code /}
   */
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
   * AJAX variant of {@link #markAnnouncementAsRead}: marks the announcement read and answers with a
   * bare 200 so the home page removes the "mark read" control in place (epic #571, REQ-FE-005)
   * instead of reloading. Selected over the redirect handler only when the request carries {@code
   * X-Requested-With: XMLHttpRequest}; a script-disabled browser still posts the HTML form and
   * lands on the redirect handler, which stays the no-JS fallback. A backend failure answers 502
   * (the client leaves the control in place) — a failed mark-as-read must never break the page.
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
