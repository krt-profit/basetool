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

package de.greluc.krt.profit.basetool.backend.mission.web;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionListDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the caller's upcoming missions as list rows, the read of the home dashboard's next-mission
 * widget (REQ-MISSION-031).
 */
@RestController
@RequestMapping("/api/v1/missions/next")
@RequiredArgsConstructor
@Tag(name = "Missions", description = "Mission management endpoints")
public class MissionNextSummaryController {

  /** The number of rows returned when the caller names no limit. */
  static final int DEFAULT_LIMIT = 5;

  /** The most rows a caller may ask for. */
  static final int MAX_LIMIT = 10;

  private final MissionService missionService;
  private final MissionMapper missionMapper;

  /**
   * Returns the upcoming missions of the caller's org units merged with those the caller is signed
   * up for, as list rows that say whether the caller is signed up.
   *
   * @param limit the most rows to return, 1 to {@value #MAX_LIMIT}; {@value #DEFAULT_LIMIT} when
   *     absent
   * @return the rows, soonest planned start first; empty when no mission is upcoming
   * @throws BadRequestException when {@code limit} is outside its bounds
   */
  @GetMapping("/summary")
  @Operation(summary = "Get the next upcoming missions as list rows")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Transactional(readOnly = true)
  public List<MissionListDto> getNextMissionsSummary(
      @RequestParam(required = false) @Nullable Integer limit) {
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
    }
    List<Mission> missions = missionService.getUpcomingOwnAndSignedUp(size);
    if (missions.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = missions.stream().map(Mission::getId).toList();
    Map<UUID, Long> counts = missionService.registeredCounts(ids);
    Set<UUID> signedUp = missionService.signedUpMissionIds(ids);
    return missions.stream()
        .map(mission ->
            missionMapper.toListDto(
                mission,
                counts.getOrDefault(mission.getId(), 0L),
                signedUp.contains(mission.getId())))
        .toList();
  }
}
