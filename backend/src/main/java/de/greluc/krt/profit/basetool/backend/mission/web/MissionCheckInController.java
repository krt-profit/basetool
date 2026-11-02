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

import de.greluc.krt.profit.basetool.backend.mission.internal.MissionParticipantService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for checking every participant of a running mission in or out at once
 * (REQ-MISSION-032); only a caller who may manage the mission passes the gate.
 */
@RestController
@RequestMapping("/api/v1/missions/{id}/participants")
@RequiredArgsConstructor
public class MissionCheckInController {

  private final MissionParticipantService missionParticipantService;

  /**
   * Checks in every participant who has not checked in yet.
   *
   * @param id mission id
   * @return 204 No Content
   */
  @PostMapping("/check-in-all")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @missionAccessPolicy.canManageMission(#id, authentication)")
  @Operation(
      summary = "Check in all participants",
      description =
          "Checks in every participant who has not checked in yet; manager-only and only while the"
              + " mission is running.")
  public ResponseEntity<Void> checkInAll(@PathVariable @NotNull UUID id) {
    missionParticipantService.checkInAll(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Checks out every checked-in participant who has not checked out yet.
   *
   * @param id mission id
   * @return 204 No Content
   */
  @PostMapping("/check-out-all")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @missionAccessPolicy.canManageMission(#id, authentication)")
  @Operation(
      summary = "Check out all participants",
      description =
          "Checks out every checked-in participant who has not checked out yet; manager-only and"
              + " only while the mission is running.")
  public ResponseEntity<Void> checkOutAll(@PathVariable @NotNull UUID id) {
    missionParticipantService.checkOutAll(id);
    return ResponseEntity.noContent().build();
  }
}
