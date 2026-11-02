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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient.MissionSearch;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionListDto;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Finds missions by name through the mission-list read, so a hit is a mission the list would show
 * the caller in any period (REQ-UI-033).
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class MissionQuickAccessSearchContributor implements QuickAccessSearchContributor {

  private static final String EVERY_PERIOD = "ALL";

  private final MissionBackendClient missionClient;

  /**
   * Searches missions.
   *
   * @return {@link QuickAccessSearchKind#MISSION}
   */
  @Override
  public @NotNull QuickAccessSearchKind kind() {
    return QuickAccessSearchKind.MISSION;
  }

  /**
   * Reads the first page of matching missions, newest planned start first.
   *
   * @param query the search term
   * @param limit the page size
   * @return the missions as hits on their detail pages
   */
  @Override
  public @NotNull List<QuickAccessHit> results(@NotNull String query, int limit) {
    PageResponse<MissionListDto> page =
        missionClient.searchMissions(
            new MissionSearch(query, null, null, 0, limit, List.of(), EVERY_PERIOD));
    if (page == null || page.content() == null) {
      return List.of();
    }
    return page.content().stream()
        .map(m ->
            new QuickAccessHit(
                "/missions/" + m.id(),
                m.name(),
                m.status() == null ? null : "mission.status." + m.status().toLowerCase(Locale.ROOT),
                m.plannedStartTime()))
        .toList();
  }
}
