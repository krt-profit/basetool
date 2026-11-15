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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Creates a mission template in an explicitly chosen org unit.
 *
 * <p>A {@code null} value or an empty list leaves that part to the planner.
 *
 * @param owningOrgUnitId the owner
 * @param name the name within that owner
 * @param description the optional Markdown description
 * @param meetingPoint the optional meeting point
 * @param isInternal the optional visibility
 * @param meetingTime the optional TeamSpeak meeting time of day
 * @param plannedStartTime the optional planned start time of day
 * @param plannedEndTime the optional planned end time of day
 * @param frequencies the ordered mission-wide radio channels, {@code null} for none
 * @param objectives the ordered goals
 * @param steps the ordered procedure
 * @param units the ordered unit plan
 */
public record CreateMissionTemplateRequest(
    @NotNull UUID owningOrgUnitId,
    @NotBlank @Size(max = 255) String name,
    @Nullable @Size(max = 20000) String description,
    @Nullable @Size(max = 200) String meetingPoint,
    @Nullable Boolean isInternal,
    @Nullable LocalTime meetingTime,
    @Nullable LocalTime plannedStartTime,
    @Nullable LocalTime plannedEndTime,
    @Nullable List<MissionTemplatePlan.@NotNull @Valid Frequency> frequencies,
    @NotNull List<MissionTemplatePlan.@NotNull @Valid Objective> objectives,
    @NotNull List<MissionTemplatePlan.@NotNull @Valid Step> steps,
    @NotNull List<MissionTemplatePlan.@NotNull @Valid Unit> units) {

  /**
   * Collects the submitted parts in editor order.
   *
   * @return the unnormalized plan
   */
  MissionTemplatePlan plan() {
    return new MissionTemplatePlan(
        description,
        meetingPoint,
        isInternal,
        meetingTime,
        plannedStartTime,
        plannedEndTime,
        frequencies == null ? List.of() : frequencies,
        objectives,
        steps,
        units);
  }
}
