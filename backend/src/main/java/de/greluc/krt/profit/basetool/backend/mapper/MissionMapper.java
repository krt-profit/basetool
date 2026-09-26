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

package de.greluc.krt.profit.basetool.backend.mapper;

import de.greluc.krt.profit.basetool.backend.model.FrequencyType;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionCrew;
import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import de.greluc.krt.profit.basetool.backend.model.MissionFrequency;
import de.greluc.krt.profit.basetool.backend.model.MissionObjective;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionStep;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.FrequencyTypeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionCrewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFrequencyDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionObjectiveDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionStepDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionUnitDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.backend.support.MissionViewerAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.BeforeMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.TargetType;
import org.springframework.beans.factory.annotation.Autowired;

/** MapStruct mapper between Mission entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {ShipMapper.class, UserMapper.class, OperationMapper.class, SquadronMapper.class})
public abstract class MissionMapper {

  @Autowired protected MissionViewerAccess missionViewerAccess;

  /**
   * The same {@link UserMapper} the generated subclass delegates the roster's {@code UserDto}s to,
   * injected a second time under its own name so {@link #primeRosterUsers(Mission, Class)} can seed
   * its request memo before the nested mapping starts.
   */
  @Autowired protected UserMapper rosterUserMapper;

  /**
   * Seeds the {@link UserMapper} request memo for every participant user and ship owner of the full
   * mission DTO in two queries (REQ-DATA-003).
   *
   * <p>Runs only when the target is {@link MissionDto}.
   *
   * @param mission the mission about to be mapped; {@code null} is ignored
   * @param targetType the DTO type the current mapping method produces
   */
  @BeforeMapping
  protected void primeRosterUsers(@Nullable Mission mission, @TargetType Class<?> targetType) {
    if (mission == null || targetType != MissionDto.class) {
      return;
    }
    List<User> users = new ArrayList<>();
    for (MissionParticipant participant : mission.getParticipants()) {
      users.add(participant.getUser());
    }
    for (MissionUnit unit : mission.getAssignedUnits()) {
      if (unit.getShip() != null) {
        users.add(unit.getShip().getOwner());
      }
    }
    rosterUserMapper.primeStaffelMemberships(users);
  }

  /**
   * Maps a {@link Mission} to its full DTO, adding the caller-aware projections ({@code canEdit},
   * {@code canManageManagers}, description redaction, participant counts) and the owning org unit
   * as {@code owningSquadron}.
   *
   * @param mission the entity to project; {@code null} returns {@code null}
   * @return the mission DTO
   */
  @Mapping(target = "description", expression = "java(resolveDescription(mission))")
  @Mapping(target = "canEdit", expression = "java(resolveCanEdit(mission))")
  @Mapping(target = "canManageManagers", expression = "java(resolveCanManageManagers(mission))")
  @Mapping(
      target = "checkedInParticipants",
      expression = "java(resolveCheckedInParticipants(mission))")
  @Mapping(
      target = "registeredParticipants",
      expression = "java(resolveRegisteredParticipants(mission))")
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  public abstract MissionDto toDto(Mission mission);

  /**
   * Maps a {@link MissionParticipant} to its DTO, with the participant's org-unit affiliations as a
   * sorted reference list.
   *
   * @param participant the entity to project; {@code null} returns {@code null}
   * @return the participant DTO
   */
  @Mapping(
      target = "orgUnits",
      expression = "java(orgUnitsToReferenceDtos(participant.getOrgUnits()))")
  public abstract MissionParticipantDto toDto(MissionParticipant participant);

  /** Maps a {@link MissionUnit} to its DTO with a deterministic leader-first crew ordering. */
  @Mapping(target = "crew", expression = "java(resolveCrew(unit))")
  public abstract MissionUnitDto toDto(MissionUnit unit);

  /** Maps a {@link MissionCrew} entity to its DTO, flattening the participant id / display name. */
  @Mapping(target = "participantId", source = "participant.id")
  @Mapping(target = "participantName", expression = "java(resolveParticipantName(crew))")
  public abstract MissionCrewDto toDto(MissionCrew crew);

  /** Maps a {@link MissionFinanceEntry} to its DTO, flattening the parent mission id. */
  @Mapping(target = "missionId", source = "mission.id")
  public abstract MissionFinanceEntryDto toDto(MissionFinanceEntry entry);

  /** Maps a {@link FrequencyType} entity nested inside a mission to its outbound DTO. */
  public abstract FrequencyTypeDto toDto(FrequencyType frequencyType);

  /** Maps a {@link MissionFrequency} entity to its outbound DTO. */
  public abstract MissionFrequencyDto toDto(MissionFrequency missionFrequency);

  /** Maps a {@link MissionStep} (Ablauf step) entity to its outbound DTO. */
  public abstract MissionStepDto toDto(MissionStep missionStep);

  /** Maps a {@link MissionObjective} (mission goal / Ziel) entity to its outbound DTO. */
  public abstract MissionObjectiveDto toDto(MissionObjective missionObjective);

  /** Maps a {@link JobType} entity nested inside a mission to its outbound DTO. */
  @Mapping(target = "parentId", source = "parent.id")
  @Mapping(target = "isLeadershipRole", source = "leadershipRole")
  @Mapping(target = "isMissionLead", source = "missionLead")
  public abstract JobTypeDto toDto(JobType jobType);

  /** Narrow reference DTO (id + name) used wherever the full mission payload is overkill. */
  public abstract MissionReferenceDto toReferenceDto(Mission mission);

  /**
   * Maps a mission to its slim list-row DTO, with the same description redaction as the full DTO
   * and the owning org unit as {@code owningSquadron}.
   *
   * @param mission the entity to project; {@code null} returns {@code null}
   * @param registeredCount the mission's participant count, resolved by the caller for the page
   * @return the list-row DTO
   */
  @Mapping(target = "description", expression = "java(resolveDescription(mission))")
  @Mapping(target = "owningSquadron", source = "mission.owningOrgUnit")
  @Mapping(target = "registeredCount", expression = "java(registeredCount)")
  public abstract MissionListDto toListDto(Mission mission, long registeredCount);

  /**
   * Projects org-unit affiliations into {@link OrgUnitReferenceDto}s, Staffel first, then
   * Spezialkommandos alphabetically by name.
   *
   * @param orgUnits the affiliations; {@code null} or empty yields an empty list
   * @return the sorted reference DTOs; never {@code null}
   */
  public List<OrgUnitReferenceDto> orgUnitsToReferenceDtos(Set<OrgUnit> orgUnits) {
    if (orgUnits == null || orgUnits.isEmpty()) {
      return List.of();
    }
    return orgUnits.stream()
        .sorted(
            Comparator.<OrgUnit, Integer>comparing(
                    ou -> ou.getKind() == OrgUnitKind.SQUADRON ? 0 : 1)
                .thenComparing(
                    ou -> ou.getName() == null ? "" : ou.getName(), String.CASE_INSENSITIVE_ORDER))
        .map(
            ou ->
                new OrgUnitReferenceDto(ou.getId(), ou.getName(), ou.getShorthand(), ou.getKind()))
        .toList();
  }

  /**
   * Returns the mission description to squadron members and above, and {@code null} to anyone else
   * (REQ-SEC-009, REQ-SEC-041).
   *
   * @param mission the mission being projected; {@code null} yields {@code null}
   * @return the description for a member-or-above caller, otherwise {@code null}
   */
  @Nullable
  public String resolveDescription(Mission mission) {
    if (mission == null || mission.getDescription() == null) {
      return null;
    }
    if (missionViewerAccess.isMemberOrAbove()) {
      return mission.getDescription();
    }
    return null;
  }

  /** Returns {@code true} iff the current caller may edit this mission. */
  public boolean resolveCanEdit(Mission mission) {
    if (mission == null) {
      return false;
    }
    return missionViewerAccess.canManageMission(mission.getId());
  }

  /** Returns {@code true} iff the current caller may add/remove mission managers. */
  public boolean resolveCanManageManagers(Mission mission) {
    if (mission == null) {
      return false;
    }
    return missionViewerAccess.canManageManagers(mission.getId());
  }

  /**
   * Counts participants that have been checked in. A participant is considered checked in as soon
   * as {@code startTime} is set (see {@link
   * de.greluc.krt.profit.basetool.backend.service.MissionService#checkIn}).
   */
  public int resolveCheckedInParticipants(Mission mission) {
    if (mission == null || mission.getParticipants() == null) {
      return 0;
    }
    return (int)
        mission.getParticipants().stream()
            .filter(p -> p != null && p.getStartTime() != null)
            .count();
  }

  /** Counts all registered/enrolled participants of the mission, regardless of check-in state. */
  public int resolveRegisteredParticipants(Mission mission) {
    if (mission == null || mission.getParticipants() == null) {
      return 0;
    }
    return mission.getParticipants().size();
  }

  /**
   * Sorts a mission unit's crew with leaders first (any assigned JobType flagged as leadership),
   * then by participant display name.
   */
  public List<MissionCrewDto> resolveCrew(MissionUnit unit) {
    if (unit == null || unit.getCrew() == null) {
      return List.of();
    }
    Comparator<MissionCrew> leaderFirst =
        Comparator.comparing((MissionCrew c) -> isLeaderCrew(c) ? 0 : 1)
            .thenComparing(
                c -> {
                  String n = resolveParticipantName(c);
                  return n == null ? "" : n.toLowerCase(Locale.ROOT);
                });
    return unit.getCrew().stream().sorted(leaderFirst).map(this::toDto).toList();
  }

  private boolean isLeaderCrew(MissionCrew crew) {
    if (crew == null || crew.getJobTypes() == null) {
      return false;
    }
    for (JobType jt : crew.getJobTypes()) {
      if (jt != null && jt.isLeadershipRole()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Resolves a participant's display name: the linked user's effective name if known, otherwise the
   * guest name captured at sign-up.
   */
  @Nullable
  public String resolveParticipantName(MissionCrew crew) {
    if (crew.getParticipant() == null) {
      return null;
    }
    if (crew.getParticipant().getUser() != null) {
      return crew.getParticipant().getUser().getEffectiveName();
    }
    return crew.getParticipant().getGuestName();
  }
}
