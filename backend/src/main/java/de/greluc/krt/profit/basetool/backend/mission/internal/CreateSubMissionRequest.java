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

import de.greluc.krt.profit.basetool.backend.kernel.DtoConstraints;
import de.greluc.krt.profit.basetool.backend.mission.internal.CreateMissionRequest.NewObjective;
import de.greluc.krt.profit.basetool.backend.mission.internal.CreateMissionRequest.NewStep;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Request to attach a mission to the parent identified in the path. The parent supplies the owning
 * unit.
 */
public record CreateSubMissionRequest(
    @NotBlank @Size(max = 255) String name,
    @Nullable @Size(max = 20000) String description,
    @Nullable
        @Size(max = 2048)
        @Pattern(regexp = DtoConstraints.HTTPS_URL_REGEX, message = "must start with https://")
        String calendarLink,
    @Nullable @Size(max = 64) String status,
    @Nullable Instant meetingTime,
    @Nullable Instant plannedStartTime,
    @Nullable Instant plannedEndTime,
    @Nullable Boolean isInternal,
    @Nullable UUID operationId,
    @Nullable UUID owningOrgUnitId,
    @Nullable @Size(max = 200) String meetingPoint,
    @Nullable List<@Valid NewObjective> objectives,
    @Nullable List<@Valid NewStep> steps) {

  CreateMissionRequest toMissionRequest() {
    return new CreateMissionRequest(
        name,
        description,
        calendarLink,
        status,
        meetingTime,
        plannedStartTime,
        plannedEndTime,
        isInternal,
        operationId,
        null,
        meetingPoint,
        null,
        null,
        null,
        null);
  }
}
