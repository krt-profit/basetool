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
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the mission structure: its {@link MissionUnit}s (top-level team groupings) and their {@link
 * MissionCrew}s (ship-level participant groupings). Extracted from {@code MissionService} (L1 step
 * 2, #920) so the units/crews responsibility no longer shares that god-class's dependencies.
 *
 * <p>Units and crews are locked per row via their own JPA {@code @Version} (not a mission section
 * counter), so a unit/crew edit never collides with a concurrent core/schedule/flags/participant
 * edit. Each mutator persists the smallest owning child ({@code MissionUnit} / {@code MissionCrew})
 * and never {@code save(mission)}, keeping the mission row's {@code @Version} stable. {@code
 * MissionService} keeps its public unit/crew methods as thin delegations, so the controller and
 * transaction boundaries are unchanged.
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
   * Adds a unit (team grouping) to a mission. Units are the top level of the participant hierarchy:
   * each unit may contain several crews.
   *
   * <p>{@code name} is an optional display name: when blank, the stored name is derived from the
   * assigned ship respectively ship type (the unit-modal mock's "Anzeigename (optional)"); at least
   * one of name / ship / ship type must be present. {@code responsibleUserId} (nullable) pins an
   * explicit responsible person; {@code note} (nullable) is a free-text planning note.
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    MissionUnit missionUnit = new MissionUnit();
    missionUnit.setMission(mission);

    if (shipTypeId != null) {
      ShipType shipType =
          shipTypeRepository
              .findById(shipTypeId)
              .orElseThrow(() -> new NotFoundException("ShipType not found"));
      missionUnit.setShipType(shipType);
    } else {
      missionUnit.setShipType(null);
    }

    if (shipId != null) {
      Ship ship =
          shipRepository
              .findById(shipId)
              .orElseThrow(() -> new NotFoundException("Ship not found"));
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
   * Resolves the stored (NOT NULL) unit name from the optional display name: a non-blank caller
   * value wins; otherwise the name is derived from the already-resolved ship (its hangar name)
   * respectively ship type. Mirrors the unit-modal mock where the Anzeigename is optional because
   * ship / ship type carry the unit's identity.
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
   * @param responsibleUserId the user id, or {@code null} for "no explicit responsible" (the UI
   *     then falls back to the assigned ship's owner)
   * @return the resolved user or {@code null}
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the id is
   *     unknown
   */
  private User resolveResponsibleUser(UUID responsibleUserId) {
    if (responsibleUserId == null) {
      return null;
    }
    return userRepository
        .findById(responsibleUserId)
        .orElseThrow(() -> new NotFoundException("Responsible user not found"));
  }

  /**
   * Updates a unit's name and the assigned ship. Per-unit optimistic lock — concurrent unit edits
   * across the mission don't collide.
   */
  @Transactional
  public Mission updateMissionUnit(
      @NotNull UUID missionId,
      @NotNull UUID unitId,
      String name,
      UUID shipTypeId,
      UUID shipId,
      boolean highValueUnit,
      Double frequency,
      UUID responsibleUserId,
      String note) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    MissionUnit missionUnit =
        mission.getAssignedUnits().stream()
            .filter(u -> u.getId().equals(unitId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("MissionUnit not found"));

    missionUnit.setHighValueUnit(highValueUnit);
    missionUnit.setResponsibleUser(resolveResponsibleUser(responsibleUserId));
    missionUnit.setNote(StringNormalization.trimToNull(note));

    if (shipTypeId != null) {
      ShipType shipType =
          shipTypeRepository
              .findById(shipTypeId)
              .orElseThrow(() -> new NotFoundException("ShipType not found"));
      missionUnit.setShipType(shipType);
    } else {
      missionUnit.setShipType(null);
    }

    if (shipId != null) {
      Ship ship =
          shipRepository
              .findById(shipId)
              .orElseThrow(() -> new NotFoundException("Ship not found"));
      if (shipTypeId != null && !ship.getShipType().getId().equals(shipTypeId)) {
        throw new IllegalArgumentException("Ship does not match the specified ShipType");
      }
      // A ship already pinned to any unit of this mission is grandfathered: unrelated edits (name,
      // frequency, HVU) on a unit whose ship owner has since left the roster must not 400, and the
      // edit picker keeps offering every already-assigned ship so it round-trips. Only a ship new
      // to the mission must belong to a current participant.
      boolean alreadyAssignedInMission =
          mission.getAssignedUnits().stream()
              .map(MissionUnit::getShip)
              .filter(assigned -> assigned != null)
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

    // After ship / ship type resolution so a blank display name can derive from them (same rule
    // as addUnitToMission).
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
   * Tests whether the given ship's owner is signed up as a participant of the mission. Only
   * participants backed by a real {@link User} account count — guest participants have no account
   * and therefore never own hangar ships. Used by {@link #addUnitToMission} and {@link
   * #updateMissionUnit} to keep a unit's assigned ship constrained to ships brought by people
   * actually registered for the mission.
   *
   * @param mission the mission whose participant roster is searched, never {@code null}
   * @param ship the ship whose owner is checked for participation, never {@code null}
   * @return {@code true} if the ship's owner is a registered (account-backed) participant
   */
  private boolean isOwnerRegisteredParticipant(@NotNull Mission mission, @NotNull Ship ship) {
    UUID ownerId = ship.getOwner().getId();
    return mission.getParticipants().stream()
        .map(MissionParticipant::getUser)
        .filter(user -> user != null)
        .anyMatch(user -> user.getId().equals(ownerId));
  }

  /**
   * Collects the ships a unit of this mission may be crewed with: every ship owned by a registered
   * participant <em>plus</em> every ship already pinned to one of the mission's units. Participant
   * ships are intentionally NOT OrgUnit-scoped — a participant brings their own ship regardless of
   * which OrgUnit they belong to (so a cross-OrgUnit participant's ship becomes selectable), which
   * is why this reads {@link ShipRepository#findByOwnerIdIn} rather than the scoped hangar query.
   * Already-assigned ships are kept so editing a unit never silently drops a ship whose owner has
   * since left the roster. The result is deduplicated by ship id, participant ships first.
   *
   * @param missionId the mission whose selectable unit ships are collected, never {@code null}
   * @return the candidate ships for this mission's unit ship pickers
   * @throws NotFoundException when the mission id does not resolve
   */
  @Transactional(readOnly = true)
  public List<Ship> getSelectableUnitShips(@NotNull UUID missionId) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    java.util.Map<UUID, Ship> byId = new java.util.LinkedHashMap<>();

    Set<UUID> participantUserIds =
        mission.getParticipants().stream()
            .map(MissionParticipant::getUser)
            .filter(user -> user != null)
            .map(User::getId)
            .collect(java.util.stream.Collectors.toSet());
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

    return new java.util.ArrayList<>(byId.values());
  }

  /**
   * Removes a unit. Participants that were assigned to the unit fall back to the unassigned bucket
   * (their {@code unit}/{@code crew} references are cleared, the rows themselves stay).
   */
  @Transactional
  public Mission removeMissionUnit(@NotNull UUID missionId, @NotNull UUID unitId) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    MissionUnit missionShip =
        mission.getAssignedUnits().stream()
            .filter(ms -> ms != null && ms.getId() != null && ms.getId().equals(missionUnitId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("MissionUnit not found in this mission"));

    MissionParticipant participant =
        mission.getParticipants().stream()
            .filter(p -> p.getId().equals(participantId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Participant not found in this mission"));

    boolean isAlreadyAssigned =
        mission.getAssignedUnits().stream()
            .flatMap(u -> u.getCrew().stream())
            .anyMatch(c -> c.getParticipant().getId().equals(participantId));

    if (isAlreadyAssigned) {
      throw new de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException(
          "error.mission.crew.duplicate");
    }

    MissionCrew crew = new MissionCrew();
    crew.setMissionUnit(missionShip);
    crew.setParticipant(participant);

    // Fetch and validate JobTypes
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

  /** Updates a crew's name, role, and assigned ship. */
  @Transactional
  public Mission updateCrewInShip(
      @NotNull UUID missionId,
      @NotNull UUID missionUnitId,
      @NotNull UUID crewId,
      @NotNull Set<UUID> jobTypeIds) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    MissionUnit missionUnit =
        mission.getAssignedUnits().stream()
            .filter(u -> u.getId().equals(missionUnitId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("MissionUnit not found"));

    MissionCrew crew =
        missionUnit.getCrew().stream()
            .filter(c -> c.getId().equals(crewId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Crew member not found in this unit"));

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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    MissionUnit missionUnit =
        mission.getAssignedUnits().stream()
            .filter(u -> u.getId().equals(missionUnitId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("MissionUnit not found"));

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
   * Resolves and archetype-validates the crew job-type references. Every id must resolve to a
   * {@link JobType} of archetype {@link JobTypeArchetype#CREW}; an unknown id 404s and a
   * wrong-archetype id 400s. A {@code null} / empty set yields an empty set (a crew with no roles).
   *
   * @param jobTypeIds the caller-submitted crew job-type ids (nullable)
   * @return the resolved, archetype-checked job types
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when an id is unknown
   * @throws IllegalArgumentException when a resolved job type is not of archetype {@code CREW}
   */
  private Set<JobType> validateAndFetchJobTypes(Set<UUID> jobTypeIds) {
    Set<JobType> jobTypes = new HashSet<>();
    if (jobTypeIds != null && !jobTypeIds.isEmpty()) {
      for (UUID jtId : jobTypeIds) {
        JobType jt =
            jobTypeRepository
                .findById(jtId)
                .orElseThrow(() -> new NotFoundException("JobType not found: " + jtId));

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
