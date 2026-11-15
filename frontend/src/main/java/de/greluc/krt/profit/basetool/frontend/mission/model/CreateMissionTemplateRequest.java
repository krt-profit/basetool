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

package de.greluc.krt.profit.basetool.frontend.mission.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Payload for creating an org unit's template; a {@code null} value or an empty list leaves that
 * part to the planner.
 *
 * @param owningOrgUnitId the explicitly selected owning org unit
 * @param name the template name
 * @param description the optional Markdown description
 * @param meetingPoint the optional meeting point
 * @param isInternal the optional visibility
 * @param meetingTime the optional TeamSpeak meeting time of day
 * @param plannedStartTime the optional planned start time of day
 * @param plannedEndTime the optional planned end time of day
 * @param frequencies the ordered mission-wide radio channels
 * @param objectives the ordered goals
 * @param steps the ordered procedure
 * @param units the ordered unit slots
 */
@DtoMirror
public record CreateMissionTemplateRequest(
    UUID owningOrgUnitId,
    String name,
    String description,
    String meetingPoint,
    Boolean isInternal,
    LocalTime meetingTime,
    LocalTime plannedStartTime,
    LocalTime plannedEndTime,
    List<MissionTemplatePlan.Frequency> frequencies,
    List<MissionTemplatePlan.Objective> objectives,
    List<MissionTemplatePlan.Step> steps,
    List<MissionTemplatePlan.Unit> units) {}
