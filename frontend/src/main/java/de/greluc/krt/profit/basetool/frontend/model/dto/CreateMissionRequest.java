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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend's write-only {@code CreateMissionRequest} for {@code POST
 * /api/v1/missions}, separate from {@link MissionDto}.
 *
 * <p>{@link #owningOrgUnitId} is the owner picker output (REQ-ORG-016, REQ-ORG-017); {@code null}
 * lets the backend stamp the owner. {@link #objectives} and {@link #steps} carry the optional Ziele
 * and Ablauf rows created with the mission, {@code null} when none.
 */
public record CreateMissionRequest(
    String name,
    String description,
    String calendarLink,
    String status,
    Instant meetingTime,
    Instant plannedStartTime,
    Instant plannedEndTime,
    Boolean isInternal,
    UUID operationId,
    UUID owningOrgUnitId,
    String meetingPoint,
    List<NewObjective> objectives,
    List<NewStep> steps) {

  /**
   * A goal (Ziel) seeded together with the mission; mirrors the backend's {@code
   * CreateMissionRequest.NewObjective}.
   *
   * @param title the goal text
   * @param kind the classification enum name ("PRIMARY" / "SECONDARY" / "NON_GOAL"), mapped to the
   *     backend's {@code MissionObjectiveKind}
   */
  public record NewObjective(String title, @BackendEnumAsString String kind) {}

  /**
   * A step (Ablauf-Schritt) seeded together with the mission; mirrors the backend's {@code
   * CreateMissionRequest.NewStep}.
   *
   * @param title the step title
   * @param meta the optional free-text time/place hint
   */
  public record NewStep(String title, String meta) {}
}
