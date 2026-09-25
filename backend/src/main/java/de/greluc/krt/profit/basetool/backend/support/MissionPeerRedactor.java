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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionUnitDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Redacts mission DTOs for a member below Logistician (REQ-SEC-007): owner and managers are hidden
 * from readers and every nested user is reduced to the public callsign tuple (REQ-SEC-040).
 *
 * <p>Each DTO is rebuilt field by field so that a new field fails compilation until someone decides
 * whether a peer may see it; every nested record that reaches a user needs its own {@code
 * cleanup…ForPeer} pass. The method names are matched by an ArchUnit rule.
 */
@Component
public class MissionPeerRedactor {

  /**
   * Redacts a mission DTO for a peer: hides owner and managers from a reader, keeps the caller's
   * own capability flags, and cleans each participant.
   *
   * <p>A caller who may manage the mission keeps the owner and manager list.
   *
   * @param dto the full mission DTO
   * @return a redacted copy safe for a member below Logistician
   */
  @NotNull
  public MissionDto cleanupMissionForPeer(@NotNull MissionDto dto) {
    boolean managing =
        Boolean.TRUE.equals(dto.canEdit()) || Boolean.TRUE.equals(dto.canManageManagers());

    Set<MissionParticipantDto> cleanedParticipants =
        dto.participants() == null
            ? null
            : dto.participants().stream()
                .map(this::cleanupParticipantForPeer)
                .collect(Collectors.toSet());

    List<MissionUnitDto> cleanedUnits =
        dto.assignedUnits() == null
            ? null
            : dto.assignedUnits().stream().map(this::cleanupUnitForPeer).toList();

    return new MissionDto(
        dto.id(),
        dto.name(),
        dto.description(),
        dto.calendarLink(),
        dto.status(),
        dto.meetingTime(),
        dto.plannedStartTime(),
        dto.actualStartTime(),
        dto.plannedEndTime(),
        dto.actualEndTime(),
        dto.isInternal(),
        cleanedParticipants,
        cleanedUnits,
        dto.frequencies(),
        dto.operation(),
        managing ? dto.owner() : null,
        managing ? dto.managers() : null,
        dto.canEdit(),
        dto.canManageManagers(),
        dto.version(),
        dto.coreVersion(),
        dto.scheduleVersion(),
        dto.flagsVersion(),
        dto.checkedInParticipants(),
        dto.registeredParticipants(),
        dto.owningSquadron(),
        dto.owningOrgUnitVersion(),
        dto.partyLeadUser(),
        dto.partyLeadGuestName(),
        dto.partyLeadVersion(),
        dto.steps(),
        dto.stepsVersion(),
        dto.objectives(),
        dto.objectivesVersion(),
        dto.meetingPoint(),
        dto.ownershipVersion());
  }

  /**
   * Redacts an assigned unit for a peer by cleaning its nested {@link ShipDto} (REQ-SEC-040).
   *
   * @param dto the unit DTO from the mapper; never {@code null}
   * @return a copy whose ship owner is reduced to the public callsign tuple
   */
  @NotNull
  public MissionUnitDto cleanupUnitForPeer(@NotNull MissionUnitDto dto) {
    return new MissionUnitDto(
        dto.id(),
        dto.name(),
        dto.shipType(),
        dto.ship() == null ? null : cleanupShipForPeer(dto.ship()),
        dto.frequency(),
        dto.highValueUnit(),
        dto.responsibleUser(),
        dto.note(),
        dto.version(),
        dto.crew());
  }

  /**
   * Redacts a ship DTO for a peer by reducing its owner via {@link #cleanupUserForPeer}
   * (REQ-SEC-040).
   *
   * @param dto the ship DTO nested in an assigned unit; never {@code null}
   * @return a copy whose owner is redacted for a peer
   */
  @NotNull
  public ShipDto cleanupShipForPeer(@NotNull ShipDto dto) {
    return new ShipDto(
        dto.id(),
        dto.name(),
        dto.shipType(),
        dto.insurance(),
        dto.location(),
        dto.fitted(),
        dto.owner() == null ? null : cleanupUserForPeer(dto.owner()),
        dto.owningSquadron(),
        dto.version());
  }

  /**
   * Redacts a participant DTO for a peer by cleaning the nested user via {@link
   * #cleanupUserForPeer}; all other fields are kept.
   *
   * @param dto the participant DTO
   * @return a redacted copy safe for a member below Logistician
   */
  @NotNull
  public MissionParticipantDto cleanupParticipantForPeer(@NotNull MissionParticipantDto dto) {
    UserDto cleanedUser = dto.user() != null ? cleanupUserForPeer(dto.user()) : null;
    return new MissionParticipantDto(
        dto.id(),
        cleanedUser,
        dto.guestName(),
        dto.orgUnits(),
        dto.desiredMissionJobType(),
        dto.plannedMissionJobType(),
        dto.comment(),
        dto.startTime(),
        dto.endTime(),
        dto.payoutPreference(),
        dto.version());
  }

  /**
   * Redacts a user DTO for a peer down to username, display name and rank, dropping email,
   * description, roles, permissions, announcement watermark, join date and squadrons.
   *
   * <p>Unlike {@code UserDtoRedaction.toPeerShape}, this also nulls {@code squadron} and {@code
   * squadrons}.
   *
   * @param dto the user DTO, or {@code null}
   * @return a redacted copy safe for a member below Logistician, or {@code null} for a {@code null}
   *     input
   */
  @Nullable
  @Contract("null -> null; !null -> !null")
  public UserDto cleanupUserForPeer(@Nullable UserDto dto) {
    if (dto == null) {
      return null;
    }
    return new UserDto(
        dto.id(),
        dto.username(),
        dto.displayName(),
        dto.effectiveName(),
        null,
        dto.rank(),
        null,
        null,
        null,
        null,
        false,
        false,
        dto.inKeycloak(),
        null,
        null,
        dto.version(),
        null,
        null);
  }
}
