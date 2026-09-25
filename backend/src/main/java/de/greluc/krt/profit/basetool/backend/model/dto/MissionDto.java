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

package de.greluc.krt.profit.basetool.backend.model.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Data transfer record carrying the mission detail payload.
 *
 * <p>{@code version} guards the full-update endpoint; the section-scoped endpoints validate their
 * own counters ({@code coreVersion}, {@code scheduleVersion}, {@code flagsVersion}, {@code
 * partyLeadVersion}, {@code owningOrgUnitVersion}, {@code ownershipVersion}), so edits of disjoint
 * sections do not conflict. {@code partyLeadUser} and {@code partyLeadGuestName} are mutually
 * exclusive; {@code owningSquadron} may be {@code null}. Finance, refinery and inventory data are
 * not embedded and load through their own endpoints.
 */
public record MissionDto(
    UUID id,
    @NotBlank String name,
    String description,
    String calendarLink,
    String status,
    Instant meetingTime,
    Instant plannedStartTime,
    Instant actualStartTime,
    Instant plannedEndTime,
    Instant actualEndTime,
    Boolean isInternal,
    Set<MissionParticipantDto> participants,
    List<MissionUnitDto> assignedUnits,
    List<MissionFrequencyDto> frequencies,
    OperationDto operation,
    UserReferenceDto owner,
    Set<UserReferenceDto> managers,
    Boolean canEdit,
    Boolean canManageManagers,
    Long version,
    Long coreVersion,
    Long scheduleVersion,
    Long flagsVersion,
    Integer checkedInParticipants,
    Integer registeredParticipants,
    SquadronReferenceDto owningSquadron,
    Long owningOrgUnitVersion,
    UserReferenceDto partyLeadUser,
    String partyLeadGuestName,
    Long partyLeadVersion,
    List<MissionStepDto> steps,
    Long stepsVersion,
    List<MissionObjectiveDto> objectives,
    Long objectivesVersion,
    String meetingPoint,
    Long ownershipVersion) {}
