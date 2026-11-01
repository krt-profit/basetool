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

import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionListDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the caller's next upcoming mission as one list row, the read of the home dashboard's
 * next-mission widget (REQ-MISSION-031).
 */
@RestController
@RequestMapping("/api/v1/missions/next")
@RequiredArgsConstructor
@Tag(name = "Missions", description = "Mission management endpoints")
public class MissionNextSummaryController {

  private final MissionService missionService;
  private final MissionMapper missionMapper;

  /**
   * Returns the mission of {@code GET /api/v1/missions/next} as a list row that says whether the
   * caller is signed up.
   *
   * @return the mission's list row, or 204 No Content when no mission is upcoming
   */
  @GetMapping("/summary")
  @Operation(summary = "Get the next upcoming mission as a list row")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Transactional(readOnly = true)
  public ResponseEntity<MissionListDto> getNextMissionSummary() {
    return missionService
        .getNextMission()
        .map(this::toRow)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.noContent().build());
  }

  private MissionListDto toRow(Mission mission) {
    List<UUID> ids = List.of(mission.getId());
    long registered = missionService.registeredCounts(ids).getOrDefault(mission.getId(), 0L);
    boolean signedUp = missionService.signedUpMissionIds(ids).contains(mission.getId());
    return missionMapper.toListDto(mission, registered, signedUp);
  }
}
