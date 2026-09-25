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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionCrew;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionCrewRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages a mission's {@link MissionUnit}s and their {@link MissionCrew}s.
 *
 * <p>Units and crews are locked per row and never save the mission, so their edits never collide
 * with other mission sections. Updates check the client-echoed {@code expectedVersion} via {@link
 * OptimisticLock#checkOptionalClient}, because a full-form rewrite never trips the entity
 * {@code @Version}.
 */
@Service
@RequiredArgsConstructor
public class MissionStructureService {

  /** Repository for the mission aggregate root (fetch-or-throw). */
  private final MissionRepository missionRepository;

  /** Repository used to persist a newly added / edited {@link MissionUnit}. */
  private final MissionUnitRepository missionUnitRepository;

  /** Repository used to persist a newly added / edited {@link MissionCrew}. */
  private final MissionCrewRepository missionCrewRepository;

  /** Repository used to resolve and validate a unit's assigned {@link Ship}. */
  private final ShipRepository shipRepository;

  /** Repository used to resolve a unit's {@link ShipType}. */
  private final ShipTypeRepository shipTypeRepository;

  /** Repository used to resolve and archetype-check the crew {@link JobType}s. */
  private final JobTypeRepository jobTypeRepository;

  /** Repository used to resolve a unit's optional explicit responsible {@link User}. */
  private final UserRepository userRepository;

  /** Records the state-mutating unit/crew activities into the audit log (REQ-AUDIT-001). */
  private final AuditService auditService;

  /**
   * Adds a unit (top-level team grouping) to a mission.
   *
   * <p>At least one of name, ship or ship type must be present; a blank name is derived from the
   * ship or ship type.
   */
  @Transactional
  public Mission addUnitToMission(
      @NotNull UUID missionId,
      String name,
      UUID shipTypeId,
      UUID shipId,
      boolean highValueUnit,
      Double frequency,
      UUID responsibleUserId,
      String note) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    MissionUnit missionUnit = new MissionUnit();
    missionUnit.setMission(mission);

    if (shipTypeId != null) {
      ShipType shipType =
          Entities.require(shipTypeRepository.findById(shipTypeId), "ShipType not found");
      missionUnit.setShipType(shipType);
    } else {
      missionUnit.setShipType(null);
    }

    if (shipId != null) {
      Ship ship = Entities.require(shipRepository.findById(shipId), "Ship not found");
      if (shipTypeId != null && !ship.getShipType().getId().equals(shipTypeId)) {
        throw new IllegalArgumentException("Ship does not match the specified ShipType");
      }
      if (!isOwnerRegisteredParticipant(mission, ship)) {
        throw new IllegalArgumentException(
            "Ship owner is not a registered participant of the mission");
      }
      missionUnit.setShip(ship);
      if (shipTypeId == null) {
        missionUnit.setShipType(ship.getShipType());
      }
    }

    missionUnit.setName(resolveUnitName(name, missionUnit));
    missionUnit.setResponsibleUser(resolveResponsibleUser(responsibleUserId));
    missionUnit.setNote(StringNormalization.trimToNull(note));
    missionUnit.setHighValueUnit(highValueUnit);

    if (frequency != null) {
      double roundedFrequency =
          BigDecimal.valueOf(frequency).setScale(2, RoundingMode.HALF_UP).doubleValue();

      if (roundedFrequency < 100.00 || roundedFrequency > 999.99) {
        throw new IllegalArgumentException("Frequency must be between 100.00 and 999.99");
      }
      missionUnit.setFrequency(roundedFrequency);
    }

    mission.getAssignedUnits().add(missionUnit);
    missionUnitRepository.save(missionUnit);
    auditService.record(
        AuditEventType.MISSION_UNIT_ADDED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("unit", missionUnit.getId()));
    return mission;
  }

  /**
   * Resolves the stored unit name: a non-blank caller value, otherwise the ship's hangar name or
   * the ship type's name.
   *
   * @param name the caller-submitted display name (nullable/blank)
   * @param unit the unit with {@code ship} / {@code shipType} already resolved
   * @return the effective non-blank name to store
   * @throws IllegalArgumentException when neither a name nor a ship / ship type is present
   */
  private static String resolveUnitName(String name, MissionUnit unit) {
    if (name != null && !name.isBlank()) {
      return name.trim();
    }
    if (unit.getShip() != null && unit.getShip().getName() != null) {
      return unit.getShip().getName();
    }
    if (unit.getShipType() != null) {
      return unit.getShipType().getName();
    }
    throw new IllegalArgumentException(
        "Unit needs a display name or a ship / ship type to derive one from");
  }

  /**
   * Resolves the optional explicit responsible person of a unit.
   *
   * @param responsibleUserId the user id, or {@code null} for no explicit responsible
   * @return the resolved user or {@code null}
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the id is
   *     unknown
   */
  @Contract("null -> null")
  @Nullable
  private User resolveResponsibleUser(UUID responsibleUserId) {
    if (responsibleUserId == null) {
      return null;
    }
    return Entities.require(
        userRepository.findById(responsibleUserId), "Responsible user not found");
  }

  /**
   * Updates a unit from the edit form, rejecting a stale {@code expectedVersion} with a 409; only
   * the unit row is written.
   *
   * @param missionId owning mission
   * @param unitId the unit to update
   * @param expectedVersion the {@code MissionUnit.@Version} the client last saw, or {@code null} to
   *     skip the check
   * @param name new display name (blank derives from ship / ship type)
   * @param shipTypeId new ship type, or {@code null}
   * @param shipId new ship, or {@code null}
   * @param highValueUnit new high-value-unit flag
   * @param frequency new comms frequency (100.00–999.99), or {@code null}
   * @param responsibleUserId explicit responsible person, or {@code null}
   * @param note free-text planning note
   * @return the owning mission, its {@code @Version} unchanged
   */
  @Transactional
  public Mission updateMissionUnit(
      @NotNull UUID missionId,
      @NotNull UUID unitId,
      Long expectedVersion,
      String name,
      UUID shipTypeId,
      UUID shipId,
      boolean highValueUnit,
      Double frequency,
      UUID responsibleUserId,
      String note) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    MissionUnit missionUnit =
        Entities.require(
            mission.getAssignedUnits().stream().filter(u -> u.getId().equals(unitId)).findFirst(),
            "MissionUnit not found");

    OptimisticLock.checkOptionalClient(
        missionUnit.getVersion(), expectedVersion, MissionUnit.class, unitId);

    missionUnit.setHighValueUnit(highValueUnit);
    missionUnit.setResponsibleUser(resolveResponsibleUser(responsibleUserId));
    missionUnit.setNote(StringNormalization.trimToNull(note));

    if (shipTypeId != null) {
      ShipType shipType =
          Entities.require(shipTypeRepository.findById(shipTypeId), "ShipType not found");
      missionUnit.setShipType(shipType);
    } else {
      missionUnit.setShipType(null);
    }

    if (shipId != null) {
      Ship ship = Entities.require(shipRepository.findById(shipId), "Ship not found");
      if (shipTypeId != null && !ship.getShipType().getId().equals(shipTypeId)) {
        throw new IllegalArgumentException("Ship does not match the specified ShipType");
      }
      boolean alreadyAssignedInMission =
          mission.getAssignedUnits().stream()
              .map(MissionUnit::getShip)
              .filter(Objects::nonNull)
              .anyMatch(assigned -> assigned.getId().equals(shipId));
      if (!alreadyAssignedInMission && !isOwnerRegisteredParticipant(mission, ship)) {
        throw new IllegalArgumentException(
            "Ship owner is not a registered participant of the mission");
      }
      missionUnit.setShip(ship);
      if (shipTypeId == null) {
        missionUnit.setShipType(ship.getShipType());
      }
    } else {
      missionUnit.setShip(null);
    }

    missionUnit.setName(resolveUnitName(name, missionUnit));

    if (frequency != null) {
      double roundedFrequency =
          BigDecimal.valueOf(frequency).setScale(2, RoundingMode.HALF_UP).doubleValue();

      if (roundedFrequency < 100.00 || roundedFrequency > 999.99) {
        throw new IllegalArgumentException("Frequency must be between 100.00 and 999.99");
      }
      missionUnit.setFrequency(roundedFrequency);
    } else {
      missionUnit.setFrequency(null);
    }

    missionUnitRepository.save(missionUnit);
    auditService.record(
        AuditEventType.MISSION_UNIT_UPDATED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("unit", unitId));
    return mission;
  }

  /**
   * Tests whether the ship's owner is an account-backed participant of the mission; guest
   * participants never count.
   *
   * @param mission the mission whose participant roster is searched, never {@code null}
   * @param ship the ship whose owner is checked, never {@code null}
   * @return {@code true} if the ship's owner is a registered participant
   */
  private boolean isOwnerRegisteredParticipant(@NotNull Mission mission, @NotNull Ship ship) {
    UUID ownerId = ship.getOwner().getId();
    return mission.getParticipants().stream()
        .map(MissionParticipant::getUser)
        .filter(Objects::nonNull)
        .anyMatch(user -> user.getId().equals(ownerId));
  }

  /**
   * Collects the ships selectable for this mission's units: every ship owned by a registered
   * participant, regardless of org unit, plus every ship already assigned to a unit.
   *
   * @param missionId the mission whose selectable unit ships are collected, never {@code null}
   * @return the deduplicated candidate ships, participant ships first
   * @throws NotFoundException when the mission id does not resolve
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<Ship> getSelectableUnitShips(@NotNull UUID missionId) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    Map<UUID, Ship> byId = new LinkedHashMap<>();

    Set<UUID> participantUserIds =
        mission.getParticipants().stream()
            .map(MissionParticipant::getUser)
            .filter(Objects::nonNull)
            .map(User::getId)
            .collect(Collectors.toSet());
    if (!participantUserIds.isEmpty()) {
      shipRepository
          .findByOwnerIdIn(participantUserIds)
          .forEach(ship -> byId.put(ship.getId(), ship));
    }

    for (MissionUnit unit : mission.getAssignedUnits()) {
      Ship ship = unit.getShip();
      if (ship != null) {
        byId.putIfAbsent(ship.getId(), ship);
      }
    }

    return new ArrayList<>(byId.values());
  }

  /**
   * Removes a unit; its participants stay on the mission but lose their unit and crew assignment.
   */
  @Transactional
  public Mission removeMissionUnit(@NotNull UUID missionId, @NotNull UUID unitId) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    boolean removed = mission.getAssignedUnits().removeIf(u -> u.getId().equals(unitId));

    if (!removed) {
      throw new NotFoundException("MissionUnit not found in this mission");
    }

    auditService.record(
        AuditEventType.MISSION_UNIT_REMOVED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("unit", unitId));
    return mission;
  }

  /**
   * Adds a crew (ship-level grouping) under a unit. Crews are the second level of the participant
   * hierarchy and pin participants to a specific ship's role.
   */
  @Transactional
  public Mission addCrewToShip(
      @NotNull UUID missionId,
      @NotNull UUID missionUnitId,
      @NotNull UUID participantId,
      @NotNull Set<UUID> jobTypeIds) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    MissionUnit missionShip =
        Entities.require(
            mission.getAssignedUnits().stream()
                .filter(ms -> ms != null && ms.getId() != null && ms.getId().equals(missionUnitId))
                .findFirst(),
            "MissionUnit not found in this mission");

    MissionParticipant participant =
        Entities.require(
            mission.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst(),
            "Participant not found in this mission");

    boolean isAlreadyAssigned =
        mission.getAssignedUnits().stream()
            .flatMap(u -> u.getCrew().stream())
            .anyMatch(c -> c.getParticipant().getId().equals(participantId));

    if (isAlreadyAssigned) {
      throw new DuplicateEntityException("error.mission.crew.duplicate");
    }

    MissionCrew crew = new MissionCrew();
    crew.setMissionUnit(missionShip);
    crew.setParticipant(participant);

    Set<JobType> jobTypes = validateAndFetchJobTypes(jobTypeIds);

    crew.setJobTypes(jobTypes);

    missionShip.getCrew().add(crew);
    missionCrewRepository.save(crew);
    auditService.record(
        AuditEventType.MISSION_CREW_ADDED,
        mission.getId(),
        mission.getName(),
        participant.getUser() != null ? participant.getUser().getId() : null,
        AuditDetails.of("unit", missionUnitId).with("crew", crew.getId()));
    return mission;
  }

  /**
   * Replaces a crew's job types, rejecting a stale {@code expectedVersion} with a 409; only the
   * crew row is written.
   *
   * @param missionId owning mission
   * @param missionUnitId owning unit
   * @param crewId the crew to update
   * @param expectedVersion the {@code MissionCrew.@Version} the client last saw, or {@code null} to
   *     skip the check
   * @param jobTypeIds the full replacement set of job type ids
   * @return the owning mission, its {@code @Version} unchanged
   */
  @Transactional
  public Mission updateCrewInShip(
      @NotNull UUID missionId,
      @NotNull UUID missionUnitId,
      @NotNull UUID crewId,
      Long expectedVersion,
      @NotNull Set<UUID> jobTypeIds) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    MissionUnit missionUnit =
        Entities.require(
            mission.getAssignedUnits().stream()
                .filter(u -> u.getId().equals(missionUnitId))
                .findFirst(),
            "MissionUnit not found");

    MissionCrew crew =
        Entities.require(
            missionUnit.getCrew().stream().filter(c -> c.getId().equals(crewId)).findFirst(),
            "Crew member not found in this unit");

    OptimisticLock.checkOptionalClient(
        crew.getVersion(), expectedVersion, MissionCrew.class, crewId);

    Set<JobType> jobTypes = validateAndFetchJobTypes(jobTypeIds);
    crew.setJobTypes(jobTypes);

    missionCrewRepository.save(crew);
    auditService.record(
        AuditEventType.MISSION_CREW_UPDATED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("unit", missionUnitId).with("crew", crewId));
    return mission;
  }

  /**
   * Removes a crew. Participants assigned to the crew fall back to the parent unit's unassigned
   * slot.
   */
  @Transactional
  public Mission removeCrewFromShip(
      @NotNull UUID missionId, @NotNull UUID missionUnitId, @NotNull UUID crewId) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    MissionUnit missionUnit =
        Entities.require(
            mission.getAssignedUnits().stream()
                .filter(u -> u.getId().equals(missionUnitId))
                .findFirst(),
            "MissionUnit not found");

    boolean removed = missionUnit.getCrew().removeIf(c -> c.getId().equals(crewId));

    if (!removed) {
      throw new NotFoundException("Crew member not found in this unit");
    }

    auditService.record(
        AuditEventType.MISSION_CREW_REMOVED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("unit", missionUnitId).with("crew", crewId));
    return mission;
  }

  /**
   * Resolves crew job types, each of which must be of archetype {@link JobTypeArchetype#CREW}.
   *
   * @param jobTypeIds the crew job-type ids; {@code null} or empty yields an empty set
   * @return the resolved, archetype-checked job types
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when an id is unknown
   * @throws IllegalArgumentException when a resolved job type is not of archetype {@code CREW}
   */
  @NotNull
  private Set<JobType> validateAndFetchJobTypes(Set<UUID> jobTypeIds) {
    Set<JobType> jobTypes = new HashSet<>();
    if (jobTypeIds != null && !jobTypeIds.isEmpty()) {
      for (UUID jtId : jobTypeIds) {
        JobType jt =
            Entities.require(jobTypeRepository.findById(jtId), () -> "JobType not found: " + jtId);

        if (jt.getArchetype() != JobTypeArchetype.CREW) {
          throw new IllegalArgumentException(
              "JobType " + jt.getName() + " is not of archetype CREW");
        }
        jobTypes.add(jt);
      }
    }
    return jobTypes;
  }
}
