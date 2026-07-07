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
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

/** MapStruct mapper between Mission entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {ShipMapper.class, UserMapper.class, OperationMapper.class, SquadronMapper.class})
public abstract class MissionMapper {

  // MapStruct generates a concrete subclass via the annotation processor; the generated subclass
  // cannot accept additional constructor parameters, so we fall back to field-level @Autowired
  // here.
  // The mapper depends ONLY on the MissionViewerAccess leaf interface (support package), never on
  // Spring's SecurityContextHolder (ArchUnit mapperLayerShouldNotReachIntoSecurityContext) and —
  // since the cycle cleanup, ADR-0047 — never on the service layer directly: the implementation
  // (MissionViewerAccessService) is what wires AuthHelperService + MissionSecurityService, so the
  // mapper -> service edge that closed the mapper <-> service package cycle is gone.
  @Autowired protected MissionViewerAccess missionViewerAccess;

  /**
   * Full {@link Mission} -&gt; DTO mapping. The five {@code resolve*} expressions are applied on
   * top of the default field copy so the DTO carries the caller-aware projections ({@code canEdit},
   * {@code canManageManagers}, description redaction for guests, participant counts).
   *
   * <p>After R9 Step 2 the mission entity exposes {@code owningOrgUnit} (typed {@code OrgUnit});
   * the DTO still publishes {@code owningSquadron} as {@code SquadronReferenceDto} for API
   * stability. The explicit mapping routes the source through {@code
   * SquadronMapper.orgUnitToReferenceDto}, which projects either kind — a Staffel or a
   * Spezialkommando — into the slim owner reference (id/name/shorthand), so SK-owned missions now
   * surface their SK badge instead of a blank cell.
   *
   * @param mission the mission entity to project; {@code null} returns {@code null}.
   * @return the populated mission DTO.
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
   * Maps a {@link MissionParticipant} entity to its outbound DTO. The {@code orgUnits} target is
   * filled by {@link #orgUnitsToReferenceDtos(java.util.Set)} so the participant's Staffel and/or
   * Spezialkommando affiliations surface as a sorted reference list rather than the former single
   * squadron field.
   *
   * @param participant the participant entity to project; {@code null} returns {@code null}.
   * @return the populated participant DTO.
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
  public abstract JobTypeDto toDto(JobType jobType);

  /** Narrow reference DTO (id + name) used wherever the full mission payload is overkill. */
  public abstract MissionReferenceDto toReferenceDto(Mission mission);

  /**
   * Slim list-row DTO of a mission; same description redaction as the full DTO. Also routes the
   * mission's {@code owningOrgUnit} through {@code SquadronMapper.orgUnitToReferenceDto} for the
   * {@code owningSquadron} DTO slot so the column on the missions list renders without an extra
   * round-trip, projecting either a Staffel or a Spezialkommando owner into the slim reference.
   *
   * @param mission the mission entity to project; {@code null} returns {@code null}.
   * @return the slim list-row DTO.
   */
  @Mapping(target = "description", expression = "java(resolveDescription(mission))")
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  public abstract MissionListDto toListDto(Mission mission);

  // toEntity(MissionDto) has been removed (audit finding C-3, 2026-05-20): the previous mapper
  // copied id / version / owningSquadron / parent / isInternal straight from the response DTO
  // into a fresh Mission entity, which made `missionRepository.save(entity)` invoke
  // EntityManager.merge() and overwrite an attacker-supplied existing row. Write paths now go
  // through dedicated CreateMissionRequest / UpdateMissionRequest records that physically lack
  // those fields. The ArchUnit rule {@code missionDtoMustNotBeAcceptedAsRequestBody} keeps this
  // direction one-way.

  /**
   * Projects a participant's {@code Set<OrgUnit>} affiliations into a deterministically ordered
   * list of {@link OrgUnitReferenceDto} — Staffel first, then Spezialkommandos alphabetically by
   * name — mirroring the order {@code OrgUnitMembershipService} uses for membership pickers so the
   * roster badges and the sign-up picker render consistently. Each org unit's {@code kind} is taken
   * from the entity's {@code getKind()} discriminator (no lazy-proxy {@code instanceof} pitfall).
   *
   * @param orgUnits the participant's affiliations; {@code null} or empty yields an empty list.
   * @return the sorted reference DTOs; never {@code null}.
   */
  public java.util.List<OrgUnitReferenceDto> orgUnitsToReferenceDtos(
      java.util.Set<OrgUnit> orgUnits) {
    if (orgUnits == null || orgUnits.isEmpty()) {
      return java.util.List.of();
    }
    return orgUnits.stream()
        .sorted(
            java.util.Comparator.<OrgUnit, Integer>comparing(
                    ou -> ou.getKind() == OrgUnitKind.SQUADRON ? 0 : 1)
                .thenComparing(
                    ou -> ou.getName() == null ? "" : ou.getName(), String.CASE_INSENSITIVE_ORDER))
        .map(
            ou ->
                new OrgUnitReferenceDto(ou.getId(), ou.getName(), ou.getShorthand(), ou.getKind()))
        .toList();
  }

  /**
   * Returns the mission description only to authenticated callers; guests get {@code null} so the
   * description is never exposed via the public detail endpoint.
   */
  public String resolveDescription(Mission mission) {
    if (mission == null || mission.getDescription() == null) {
      return null;
    }
    if (missionViewerAccess.isAuthenticated()) {
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
   * Sorts crew members of a mission unit so that leadership roles appear first. A crew member is
   * considered a leader if at least one of its assigned JobTypes is flagged as leadership role
   * (independent of archetype so CREW and MISSION leadership JobTypes both qualify; MISSION
   * semantics remain unchanged). Secondary sort is stable by participant display name to keep the
   * previous alphabetical ordering for non-leaders.
   */
  public java.util.List<MissionCrewDto> resolveCrew(MissionUnit unit) {
    if (unit == null || unit.getCrew() == null) {
      return java.util.List.of();
    }
    java.util.Comparator<MissionCrew> leaderFirst =
        java.util.Comparator.comparing((MissionCrew c) -> isLeaderCrew(c) ? 0 : 1)
            .thenComparing(
                c -> {
                  String n = resolveParticipantName(c);
                  return n == null ? "" : n.toLowerCase(java.util.Locale.ROOT);
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
