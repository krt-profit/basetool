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

package de.greluc.krt.profit.basetool.frontend.model.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Form-binding object for mission input.
 *
 * <p>{@code version} is the mission counter used on create; updates lock per section through {@code
 * coreVersion}, {@code scheduleVersion} and {@code flagsVersion}, so disjoint sections can be
 * edited concurrently.
 *
 * <p>{@code calendarLink} must start with {@code https://}, since it is rendered as a link and
 * {@code th:href} does not check the scheme.
 *
 * <p>{@code owningOrgUnitId} is the owner-picker output, resolved by the backend (REQ-ORG-016);
 * {@code null} leaves the stamp to the resolver.
 *
 * <p>{@code objectivesJson} / {@code stepsJson} carry the create form's optional Ziele / Ablauf
 * rows as JSON arrays and are used only on create.
 *
 * <p>{@code dirtyCore} / {@code dirtySchedule} / {@code dirtyFlags} mark which header sections the
 * user touched, so the edit save skips untouched ones (REQ-FE-014); {@code null} means "save this
 * section".
 */
public record MissionForm(
    @NotBlank(message = "{validation.name.required}") @Size(max = 255) String name,
    @Size(max = 20000) String description,
    @Size(max = 2048)
        @Pattern(regexp = "^(https://.*)?$", message = "{validation.calendarLink.httpsOnly}")
        String calendarLink,
    @NotBlank(message = "{validation.status.required}") String status,
    String meetingTime,
    String plannedStartTime,
    String plannedEndTime,
    String actualStartTime,
    String actualEndTime,
    Boolean isInternal,
    String operationId,
    Long version,
    Long coreVersion,
    Long scheduleVersion,
    Long flagsVersion,
    UUID owningOrgUnitId,
    @Size(max = 200) String meetingPoint,
    @Size(max = 65535) String objectivesJson,
    @Size(max = 65535) String stepsJson,
    Boolean dirtyCore,
    Boolean dirtySchedule,
    Boolean dirtyFlags) {}
