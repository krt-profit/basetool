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

package de.greluc.krt.profit.basetool.backend.mission.web;

import de.greluc.krt.profit.basetool.backend.hangar.api.ShipDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionUnitDto;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionPeerRedactor;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** Applies caller-specific peer redaction to mission HTTP representations. */
@org.springframework.stereotype.Component
@RequiredArgsConstructor
public class MissionHttpProjection {
  private final AuthHelperService authHelperService;
  private final MissionPeerRedactor missionPeerRedactor;

  /**
   * Applies the peer redaction to a mission DTO for non-admin callers (REQ-SEC-007).
   *
   * @param dto the freshly mapped DTO
   * @return the same DTO for admins, the peer-redacted copy for everyone else
   */
  public MissionDto cleanupForPeer(MissionDto dto) {
    return authHelperService.isAdmin() ? dto : missionPeerRedactor.cleanupMissionForPeer(dto);
  }

  /**
   * Peer pass for a single unit DTO (the slim unit endpoints).
   *
   * @param dto the freshly mapped unit
   * @return the same DTO for admins, otherwise one whose nested ship owner is reduced to the public
   *     callsign tuple (REQ-SEC-040)
   */
  public MissionUnitDto cleanupForPeer(MissionUnitDto dto) {
    return authHelperService.isAdmin() ? dto : missionPeerRedactor.cleanupUnitForPeer(dto);
  }

  /**
   * Peer pass for a single participant row (the slim participant endpoints).
   *
   * @param dto the freshly mapped participant
   * @return the same DTO for admins, otherwise one whose nested user is reduced to the public
   *     callsign tuple — no e-mail, no real name (REQ-SEC-007)
   */
  public MissionParticipantDto cleanupForPeer(MissionParticipantDto dto) {
    return authHelperService.isAdmin() ? dto : missionPeerRedactor.cleanupParticipantForPeer(dto);
  }

  /**
   * Peer pass for a participant list.
   *
   * @param participants the freshly mapped roster rows
   * @return the same list for admins, otherwise one whose nested users are reduced to the public
   *     callsign tuple
   */
  public List<MissionParticipantDto> cleanupParticipantsForPeer(
      List<MissionParticipantDto> participants) {
    return authHelperService.isAdmin()
        ? participants
        : participants.stream().map(missionPeerRedactor::cleanupParticipantForPeer).toList();
  }

  /**
   * Peer pass for a unit list.
   *
   * @param units the freshly mapped units
   * @return the same list for admins, otherwise a redacted copy
   */
  public List<MissionUnitDto> cleanupUnitsForPeer(List<MissionUnitDto> units) {
    return authHelperService.isAdmin()
        ? units
        : units.stream().map(missionPeerRedactor::cleanupUnitForPeer).toList();
  }

  /**
   * Applies the peer redaction to the unit ship picker's options, hiding the owners' personal data
   * (REQ-SEC-040).
   *
   * @param ships the selectable ships
   * @return the same list for admins, otherwise a redacted copy
   */
  public List<ShipDto> cleanupShipsForPeer(List<ShipDto> ships) {
    return authHelperService.isAdmin()
        ? ships
        : ships.stream().map(missionPeerRedactor::cleanupShipForPeer).toList();
  }
}
