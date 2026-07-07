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
import java.util.Set;
import java.util.UUID;

/**
 * Data transfer record carrying mission payload.
 *
 * <p>{@code version} is the global mission counter (legacy full-update path). The dedicated section
 * counters {@code coreVersion}, {@code scheduleVersion} and {@code flagsVersion} drive the
 * section-scoped patch endpoints; carrying them on the frontend DTO lets the mission detail page
 * pin the correct counter into each hidden form input independently.
 *
 * <p>{@code owningSquadron} mirrors the backend's squadron reference so the detail template can
 * render the owner-squadron badge consistently with the list view (MULTI_SQUADRON_PLAN.md section
 * 4.5). {@code null} for historic rows persisted before V82.
 *
 * <p>{@code partyLeadUser} / {@code partyLeadGuestName} mirror the backend's optional party lead
 * (Partyleiter) — a registered user reference or a free-text handle, mutually exclusive — so the
 * detail template can render and edit it. {@code partyLeadVersion} is the dedicated section-scoped
 * optimistic-lock counter echoed back into the party-lead edit form.
 *
 * <p>{@code owningOrgUnitVersion} mirrors the backend's section-scoped optimistic-lock counter for
 * the owning-org-unit reassignment control in the Verwaltung tab (REQ-ORG-018); the detail template
 * pins it into the reassignment form so a concurrent change surfaces a 409.
 *
 * <p><strong>Slimmed detail payload (#1138).</strong> The formerly-embedded {@code subMissions}
 * (rendered nowhere), {@code inventoryEntries} and {@code refineryOrders} were removed from the
 * mission detail DTO. The Wirtschaft block fetches the mission economy on demand instead — refinery
 * via the finance section's existing {@code refineryOrders} model attribute and inventory via a
 * dedicated {@code inventoryEntries} fetch ({@code GET /api/v1/inventory/mission/{id}}).
 */
public record MissionDto(
    UUID id,
    String name,
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
    String meetingPoint) {}
