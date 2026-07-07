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
 * Data transfer record carrying Mission payload.
 *
 * <p>The {@code version} field is the global {@link
 * de.greluc.krt.profit.basetool.backend.model.AbstractEntity#getVersion()} counter and continues to
 * guard the legacy full-update endpoint. For the section-scoped patch endpoints ({@code /core},
 * {@code /schedule}, {@code /flags}) the dedicated counters {@code coreVersion}, {@code
 * scheduleVersion} and {@code flagsVersion} are the source of truth — they allow concurrent edits
 * on disjoint sections of the same mission without spurious 409 conflicts.
 *
 * <p>{@code owningSquadron} surfaces the mission's squadron ownership on the detail endpoint,
 * matching the column already exposed by {@link MissionListDto} on the list endpoint
 * (MULTI_SQUADRON_PLAN.md section 4.5: read DTOs of staffel-scoped aggregates carry the squadron
 * mini-record). May be {@code null} for historic rows persisted before V82.
 *
 * <p>{@code partyLeadUser} / {@code partyLeadGuestName} carry the mission's optional party lead
 * (Partyleiter): a registered user reference or a free-text handle, mutually exclusive (mirroring
 * the participant {@code user}/{@code guestName} duo). {@code partyLeadVersion} is the dedicated
 * section-scoped optimistic-lock counter the party-lead endpoint validates, in the same family as
 * {@code coreVersion}/{@code scheduleVersion}/{@code flagsVersion}.
 *
 * <p>{@code owningOrgUnitVersion} is the section-scoped optimistic-lock counter for the
 * owning-org-unit reassignment endpoint (PUT {@code /api/v1/missions/{id}/owning-org-unit},
 * REQ-ORG-018). The frontend echoes it back on the next reassignment so two managers editing the
 * same mission surface a 409 instead of silently overwriting; the assigned org unit itself is
 * carried by {@link #owningSquadron}.
 *
 * <p><strong>Slimmed detail payload (#1138).</strong> The formerly-embedded {@code subMissions} (a
 * <em>recursively</em> mapped {@code Set<MissionDto>}), {@code inventoryEntries} and {@code
 * refineryOrders} were removed: {@code subMissions} was rendered nowhere, and the two economy lists
 * are unbounded and member-only. The mission economy is served on demand instead — refinery via
 * {@code GET /api/v1/refinery-orders/mission/{id}} and inventory via {@code GET
 * /api/v1/inventory/mission/{id}} — so the hottest mission read no longer drags an unbounded,
 * recursive payload through every detail GET and every mutator response.
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
    String meetingPoint) {}
