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

package de.greluc.krt.profit.basetool.frontend.mission.client;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the mission-wide check-in and check-out ({@code POST
 * /api/v1/missions/{id}/participants/check-*-all}, REQ-MISSION-032) over {@link BackendApiClient}.
 */
@Service
@RequiredArgsConstructor
public class MissionCheckInBackendClient {

  private final BackendApiClient backendApiClient;

  /**
   * Checks in every participant of the mission who has not checked in yet.
   *
   * @param id the mission
   */
  public void checkInAll(@NotNull UUID id) {
    backendApiClient.post("/api/v1/missions/{id}/participants/check-in-all", null, Void.class, id);
  }

  /**
   * Checks out every checked-in participant of the mission who has not checked out yet.
   *
   * @param id the mission
   */
  public void checkOutAll(@NotNull UUID id) {
    backendApiClient.post("/api/v1/missions/{id}/participants/check-out-all", null, Void.class, id);
  }
}
