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

package de.greluc.krt.profit.basetool.frontend.dashboard.client;

import de.greluc.krt.profit.basetool.frontend.dashboard.model.AnnouncementDto;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.AnnouncementRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionListDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Typed backend client of the dashboard: the member's upcoming missions, profile and memberships,
 * and the shared announcement with its admin editor, over {@link BackendApiClient} (plan §5.9,
 * ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class DashboardBackendClient {

  /** The backend's announcement endpoint. */
  private static final String ANNOUNCEMENT = "/api/v1/announcement";

  private static final ParameterizedTypeReference<PageResponse<MissionListDto>> MISSION_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<UUID>> UUID_LIST =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads the first 50 planned or active missions starting in a period, soonest first.
   *
   * @param start the period start
   * @param end the period end
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<MissionListDto> upcomingMissions(
      @NotNull Instant start, @NotNull Instant end) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/missions/search")
            .queryParam("start", start)
            .queryParam("end", end)
            .queryParam("sort", "plannedStartTime,asc")
            .queryParam("status", "PLANNED")
            .queryParam("status", "ACTIVE")
            .queryParam("size", 50)
            .encode()
            .build()
            .toUriString();
    return backendApiClient.get(uri, MISSION_PAGE);
  }

  /**
   * Reads the caller's own profile.
   *
   * @return the profile, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto currentUser() {
    return backendApiClient.get("/api/v1/users/me", UserDto.class);
  }

  /**
   * Reads the ids of the org units the caller is a direct member of.
   *
   * @return the ids, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<UUID> myOrgUnitIds() {
    return backendApiClient.get("/api/v1/users/me/org-unit-ids", UUID_LIST);
  }

  /**
   * Reads the announcement in force.
   *
   * @return the announcement, or {@code null} when none is active
   */
  @Nullable
  public AnnouncementDto announcement() {
    return backendApiClient.get(ANNOUNCEMENT, AnnouncementDto.class);
  }

  /**
   * Marks an announcement read for the caller.
   *
   * @param id the announcement
   */
  public void markAnnouncementRead(@NotNull UUID id) {
    backendApiClient.put("/api/v1/users/me/read-announcement/{id}", null, Void.class, id);
  }

  /**
   * Reads the stored announcement for the admin editor, also when it is blank.
   *
   * @return the announcement, or {@code null} when the backend sent no body
   */
  @Nullable
  public AnnouncementDto adminAnnouncement() {
    return backendApiClient.get(ANNOUNCEMENT + "/admin", AnnouncementDto.class);
  }

  /**
   * Saves the announcement, carrying the version it replaces.
   *
   * @param request the text and version
   */
  public void saveAnnouncement(@NotNull AnnouncementRequest request) {
    backendApiClient.put(ANNOUNCEMENT, request, Void.class);
  }

  /** Removes the announcement. */
  public void deleteAnnouncement() {
    backendApiClient.delete(ANNOUNCEMENT, Void.class);
  }
}
