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

import static de.greluc.krt.profit.basetool.backend.support.MissionSectionVersions.enforceSectionVersion;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.MissionSectionVersions.MissionSection;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the mission participant lifecycle: sign-up, attribute edits, check-in/out, payout
 * preference, removal, party lead and co-managers.
 *
 * <p>Participant writes never bump the mission's {@code @Version}; the attribute edit guards on the
 * participant's own version and the party-lead change on {@code partyLeadVersion} (REQ-ORG-018).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissionParticipantService {

  /** Repository for the mission aggregate root (fetch-or-throw). */
  private final MissionRepository missionRepository;

  /** Repository for participant rows (explicit save/flush to avoid bumping {@code Mission}). */
  private final MissionParticipantRepository missionParticipantRepository;

  /** Repository used to resolve a linked user and the guest-name promotion match. */
  private final UserRepository userRepository;

  /** Repository used to resolve the desired/planned mission job type references. */
  private final JobTypeRepository jobTypeRepository;

  /** Resolves a registered user's org-unit memberships to stamp on the participant row. */
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  /** Polymorphic repository used to materialise org-unit affiliations by id. */
  private final OrgUnitRepository orgUnitRepository;

  /** Records the state-mutating participant activities into the audit log (REQ-AUDIT-001). */
  private final AuditService auditService;

  private final MissionSecurityService missionSecurityService;

  /** Resolves a free-text participant name to a registered member or an external name. */
  private final ParticipantTargetResolver participantTargetResolver;

  /**
   * Adds a participant with a user reference or guest name, an optional desired job type and
   * comment; delegates to the full overload without org units or payout choice.
   */
  @Transactional
  public Mission addParticipant(
      @NotNull UUID missionId,
      UUID userId,
      String guestName,
      UUID desiredJobTypeId,
      String comment) {
    return addParticipant(missionId, userId, guestName, desiredJobTypeId, comment, null, null);
  }

  /**
   * Adds a participant, resolving the user from {@code userId} or by case-insensitive {@code
   * guestName} match.
   *
   * <p>A registered user's org-unit affiliations are derived from their memberships; an external
   * participant keeps the submitted {@code orgUnitIds}. A non-null {@code payoutPreference}
   * overrides the profile default (REQ-MISSION-002).
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when any referenced
   *     id is unknown
   */
  @Transactional
  public Mission addParticipant(
      @NotNull UUID missionId,
      UUID userId,
      String guestName,
      UUID desiredJobTypeId,
      String comment,
      List<UUID> orgUnitIds,
      PayoutPreference payoutPreference) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    if (mission.getParticipants() != null
        && mission.getParticipants().size() >= MissionService.MAX_PARTICIPANTS_PER_MISSION) {
      throw new BusinessConflictException(
          "Mission participant cap reached ("
              + MissionService.MAX_PARTICIPANTS_PER_MISSION
              + "). Remove inactive participants before adding more.");
    }

    ParticipantTargetResolver.ParticipantTarget target =
        participantTargetResolver.resolve(userId, guestName, "Participant name is ambiguous.");
    UUID effectiveUserId = target.userId();
    String effectiveGuestName = target.guestName();

    if (effectiveUserId == null && (effectiveGuestName == null || effectiveGuestName.isBlank())) {
      throw new IllegalArgumentException("Either User ID or Guest Name must be provided.");
    }

    final UUID finalUserId = effectiveUserId;
    final String finalGuestName = effectiveGuestName;

    if (finalUserId != null) {
      boolean exists =
          mission.getParticipants().stream()
              .anyMatch(p -> p.getUser() != null && p.getUser().getId().equals(finalUserId));
      if (exists) {
        throw new DuplicateEntityException("error.mission.participant.duplicate.user");
      }
    } else if (finalGuestName != null && !finalGuestName.isBlank()) {
      boolean exists =
          mission.getParticipants().stream()
              .anyMatch(p -> finalGuestName.equalsIgnoreCase(p.getGuestName()));
      if (exists) {
        throw new DuplicateEntityException("error.mission.participant.duplicate.guest");
      }
    }

    MissionParticipant participant = new MissionParticipant();
    participant.setMission(mission);

    if (effectiveUserId != null) {
      User user = Entities.require(userRepository.findPlainById(effectiveUserId), "User not found");
      participant.setUser(user);
      participant.setOrgUnits(resolveMembershipOrgUnits(user.getId()));
      if (user.getDefaultPayoutPreference() != null) {
        participant.setPayoutPreference(user.getDefaultPayoutPreference());
      }
    } else {
      participant.setGuestName(effectiveGuestName);
      participant.setOrgUnits(resolveSubmittedOrgUnits(orgUnitIds));
    }

    if (desiredJobTypeId != null) {
      JobType job = jobTypeRepository.findById(desiredJobTypeId).orElse(null);
      participant.setDesiredMissionJobType(job);
    }

    if (payoutPreference != null) {
      participant.setPayoutPreference(payoutPreference);
    }

    participant.setComment(comment);

    mission.getParticipants().add(participant);
    missionParticipantRepository.save(participant);
    auditService.record(
        AuditEventType.MISSION_PARTICIPANT_ADDED,
        mission.getId(),
        mission.getName(),
        finalUserId,
        AuditDetails.of("participant", participant.getId())
            .with("type", finalUserId != null ? "user" : "external"));
    return mission;
  }

  /**
   * Returns a participant of the given mission; a participant of another mission counts as not
   * found.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the participant
   *     does not exist on this mission
   */
  public MissionParticipant getParticipant(@NotNull UUID missionId, @NotNull UUID participantId) {
    return Entities.require(
        missionParticipantRepository
            .findById(participantId)
            .filter(p -> p.getMission().getId().equals(missionId)),
        "Participant not found in mission");
  }

  /**
   * Returns the participants of a mission not yet assigned to any unit or crew.
   *
   * @param missionId mission id
   * @return list of unassigned participants
   */
  public List<MissionParticipant> getUnassignedParticipants(@NotNull UUID missionId) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    Set<UUID> assignedParticipantIds =
        mission.getAssignedUnits().stream()
            .flatMap(unit -> unit.getCrew().stream())
            .map(crew -> crew.getParticipant().getId())
            .collect(Collectors.toSet());
    return mission.getParticipants().stream()
        .filter(p -> !assignedParticipantIds.contains(p.getId()))
        .toList();
  }

  /**
   * Removes a participant from a mission and the participant's linked finance entries. The
   * participant row itself is deleted (not soft-deleted); finance entries with FK to the
   * participant cascade-delete to keep the FK constraint happy.
   */
  @Transactional
  public Mission removeParticipant(@NotNull UUID missionId, @NotNull UUID participantId) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    boolean removed = mission.getParticipants().removeIf(p -> p.getId().equals(participantId));

    if (!removed) {
      throw new NotFoundException("Participant not found in this mission");
    }

    for (MissionUnit ship : mission.getAssignedUnits()) {
      ship.getCrew()
          .removeIf(
              crew ->
                  crew.getParticipant() != null
                      && crew.getParticipant().getId().equals(participantId));
    }

    auditService.record(
        AuditEventType.MISSION_PARTICIPANT_REMOVED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("participant", participantId));
    return mission;
  }

  /**
   * Updates a participant's per-mission attributes, guarded by the participant's own version; the
   * mission's version is not bumped.
   *
   * @param authentication the caller; a caller who may not manage the mission can neither set the
   *     planned job type nor rename the row onto a member or another guest
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the participant
   *     or any referenced id is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when stale
   */
  @Transactional
  public Mission updateParticipantAttributes(
      @NotNull UUID missionId,
      @NotNull UUID participantId,
      UUID desiredMissionJobTypeId,
      UUID plannedMissionJobTypeId,
      String comment,
      Instant startTime,
      Instant endTime,
      List<UUID> orgUnitIds,
      PayoutPreference payoutPreference,
      String guestName,
      Long version,
      Authentication authentication) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    MissionParticipant participant =
        Entities.require(
            mission.getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst(),
            "Participant not found in this mission");

    if (version != null && !version.equals(participant.getVersion())) {
      throw new ObjectOptimisticLockingFailureException(
          MissionParticipant.class, participant.getId());
    }

    final boolean callerMayManageMission =
        missionSecurityService.canManageLoadedMission(mission, authentication);

    if (payoutPreference != null) {
      participant.setPayoutPreference(payoutPreference);
    }

    if (desiredMissionJobTypeId != null) {
      JobType jt =
          Entities.require(
              jobTypeRepository.findById(desiredMissionJobTypeId), "Desired JobType not found");
      if (jt.getArchetype() != JobTypeArchetype.MISSION) {
        throw new IllegalArgumentException(
            "Desired JobType " + jt.getName() + " is not of archetype MISSION");
      }
      participant.setDesiredMissionJobType(jt);
    } else {
      participant.setDesiredMissionJobType(null);
    }

    if (participant.getUser() != null) {
      participant.setOrgUnits(resolveMembershipOrgUnits(participant.getUser().getId()));
    } else {
      log.info("Updating external participant: {}", participant.getId());
      if (guestName != null) {
        if (!callerMayManageMission) {
          String candidate = guestName.trim();
          if (!candidate.isEmpty()
              && !userRepository
                  .findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase(candidate, candidate)
                  .isEmpty()) {
            throw new BadRequestException("Guest name is already taken.");
          }
          boolean clashesWithAnotherGuest =
              mission.getParticipants().stream()
                  .anyMatch(
                      other ->
                          !other.getId().equals(participant.getId())
                              && candidate.equalsIgnoreCase(other.getGuestName()));
          if (clashesWithAnotherGuest) {
            throw new DuplicateEntityException("error.mission.participant.duplicate.guest");
          }
        }
        participant.setGuestName(guestName);
      }
      participant.setOrgUnits(resolveSubmittedOrgUnits(orgUnitIds));
    }

    if (!callerMayManageMission) {
      if (plannedMissionJobTypeId != null) {
        throw new AccessDeniedException(
            "Only a mission manager may assign the planned mission job type");
      }
    } else if (plannedMissionJobTypeId != null) {
      JobType jt =
          Entities.require(
              jobTypeRepository.findById(plannedMissionJobTypeId), "Planned JobType not found");
      if (jt.getArchetype() != JobTypeArchetype.MISSION) {
        throw new IllegalArgumentException(
            "Planned JobType " + jt.getName() + " is not of archetype MISSION");
      }
      if (jt.isMissionLead()) {
        boolean alreadyTaken =
            mission.getParticipants().stream()
                .anyMatch(
                    other ->
                        !other.getId().equals(participant.getId())
                            && other.getPlannedMissionJobType() != null
                            && other.getPlannedMissionJobType().isMissionLead());
        if (alreadyTaken) {
          throw new BusinessConflictException(
              "A mission can have only one Einsatzleiter (mission lead).");
        }
      }
      participant.setPlannedMissionJobType(jt);
      participant.setMissionLeadParticipant(jt.isMissionLead());
    } else {
      participant.setPlannedMissionJobType(null);
      participant.setMissionLeadParticipant(false);
    }

    participant.setComment(comment);

    if (startTime != null) {
      if (mission.getActualStartTime() == null) {
        throw new IllegalArgumentException(
            "Cannot set participant start time before mission actual start time is set");
      }
    }

    if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
      throw new IllegalArgumentException("Start time cannot be after end time");
    }

    participant.setStartTime(startTime);
    participant.setEndTime(endTime);

    missionParticipantRepository.saveAndFlush(participant);
    auditService.record(
        AuditEventType.MISSION_PARTICIPANT_UPDATED,
        mission.getId(),
        mission.getName(),
        participant.getUser() != null ? participant.getUser().getId() : null,
        AuditDetails.of("participant", participantId));
    return mission;
  }

  /**
   * Marks a participant as checked in, setting {@code startTime} only on the first check-in so the
   * credited time cannot be shortened by a repeat.
   */
  @Transactional
  public Mission checkIn(UUID missionId, UUID participantId) {
    MissionParticipant participant = getParticipant(missionId, participantId);
    Mission mission = participant.getMission();
    if (mission.getActualStartTime() == null) {
      throw new IllegalArgumentException("Cannot check in before mission actual start time is set");
    }
    if (participant.getStartTime() != null) {
      return mission;
    }
    participant.setStartTime(Instant.now());
    missionParticipantRepository.saveAndFlush(participant);
    auditService.record(
        AuditEventType.MISSION_PARTICIPANT_CHECKED_IN,
        mission.getId(),
        mission.getName(),
        participant.getUser() != null ? participant.getUser().getId() : null,
        AuditDetails.of("participant", participantId));
    return mission;
  }

  /**
   * Marks a participant as checked out. Sets {@code endTime} to {@code now()}. Unlike check-in, a
   * repeated check-out overrides the previous timestamp — late check-out corrections.
   */
  @Transactional
  public Mission checkOut(UUID missionId, UUID participantId) {
    MissionParticipant participant = getParticipant(missionId, participantId);
    Mission mission = participant.getMission();
    if (mission.getActualEndTime() != null && Instant.now().isAfter(mission.getActualEndTime())) {
      if (participant.getStartTime() != null
          && mission.getActualEndTime().isBefore(participant.getStartTime())) {
        participant.setEndTime(participant.getStartTime());
      } else {
        participant.setEndTime(mission.getActualEndTime());
      }
    } else {
      participant.setEndTime(Instant.now());
    }
    missionParticipantRepository.saveAndFlush(participant);
    auditService.record(
        AuditEventType.MISSION_PARTICIPANT_CHECKED_OUT,
        mission.getId(),
        mission.getName(),
        participant.getUser() != null ? participant.getUser().getId() : null,
        AuditDetails.of("participant", participantId));
    return mission;
  }

  /**
   * Updates a participant's payout preference (PAYOUT or DONATE). Guests can change their own
   * preference even without authentication — the security gate on the participant row keeps other
   * users' preferences locked.
   */
  @Transactional
  public Mission updatePayoutPreference(
      UUID missionId, UUID participantId, PayoutPreference preference) {
    MissionParticipant participant = getParticipant(missionId, participantId);
    Mission mission = participant.getMission();

    if (preference != null) {
      participant.setPayoutPreference(preference);
      missionParticipantRepository.save(participant);
      missionParticipantRepository.flush();
      auditService.record(
          AuditEventType.MISSION_PARTICIPANT_UPDATED,
          mission.getId(),
          mission.getName(),
          participant.getUser() != null ? participant.getUser().getId() : null,
          AuditDetails.of("participant", participantId).with("field", "payoutPreference"));
    }
    return mission;
  }

  /**
   * Sets or clears the mission's party lead, guarded by the {@code partyLeadVersion} counter
   * (REQ-ORG-018).
   *
   * @param missionId the mission id
   * @param userId the registered user to set as party lead, or {@code null}
   * @param guestName the free-text party-lead name when {@code userId} is {@code null}, or blank to
   *     clear
   * @param expectedPartyLeadVersion expected value of {@code Mission.partyLeadVersion}
   * @return the managed mission with the party lead applied
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the mission or
   *     the referenced user is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedPartyLeadVersion} is stale
   */
  @Transactional
  public Mission setPartyLead(
      @NotNull UUID missionId,
      UUID userId,
      String guestName,
      @NotNull Long expectedPartyLeadVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository, mission, MissionSection.PARTY_LEAD, expectedPartyLeadVersion, missionId);

    if (userId != null) {
      User user = Entities.require(userRepository.findPlainById(userId), "User not found");
      mission.setPartyLeadUser(user);
      mission.setPartyLeadGuestName(null);
    } else if (guestName != null && !guestName.isBlank()) {
      mission.setPartyLeadUser(null);
      mission.setPartyLeadGuestName(guestName.trim());
    } else {
      mission.setPartyLeadUser(null);
      mission.setPartyLeadGuestName(null);
    }

    Mission saved = missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_PARTY_LEAD_CHANGED,
        mission.getId(),
        mission.getName(),
        userId,
        AuditDetails.of(
            "kind",
            userId != null
                ? "user"
                : (guestName != null && !guestName.isBlank() ? "external" : "cleared")));
    return saved;
  }

  /**
   * Adds a co-manager. Co-managers can edit the mission with the same privileges as the owner
   * (except they cannot transfer ownership — see {@link MissionSecurityService#canChangeOwner}).
   */
  @Transactional
  public Mission addManager(@NotNull UUID missionId, @NotNull UUID userId) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    mission.getManagers().add(user);
    auditService.record(
        AuditEventType.MISSION_MANAGER_ADDED, mission.getId(), mission.getName(), userId, null);
    return mission;
  }

  /**
   * Removes a co-manager. The owner cannot be removed via this method — use {@link
   * MissionService#setMissionOwner} to transfer ownership first.
   */
  @Transactional
  public Mission removeManager(@NotNull UUID missionId, @NotNull UUID userId) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    mission.getManagers().removeIf(u -> u.getId().equals(userId));
    auditService.record(
        AuditEventType.MISSION_MANAGER_REMOVED, mission.getId(), mission.getName(), userId, null);
    return mission;
  }

  /**
   * Resolves every org unit a registered user belongs to, Staffel first, for stamping on the
   * participant; no memberships yield no affiliation.
   *
   * @param userId the registered user whose memberships to resolve; never {@code null}.
   * @return the managed org units in membership order; never {@code null}, possibly empty.
   */
  private List<OrgUnit> resolveMembershipOrgUnits(@NotNull UUID userId) {
    List<UUID> orgUnitIds =
        orgUnitMembershipQueryService.findAllMembershipsForUser(userId).stream()
            .map(m -> m.getId().getOrgUnitId())
            .toList();
    if (orgUnitIds.isEmpty()) {
      return List.of();
    }
    Map<UUID, OrgUnit> byId =
        orgUnitRepository.findAllById(orgUnitIds).stream()
            .collect(Collectors.toMap(OrgUnit::getId, o -> o));
    return orgUnitIds.stream().map(byId::get).filter(Objects::nonNull).toList();
  }

  /**
   * Resolves the submitted org-unit ids of an external participant into org units, in submission
   * order, skipping {@code null} and unknown ids. The affiliation is a roster label that grants
   * nothing, so no per-unit authorization applies.
   *
   * @param submittedOrgUnitIds the caller-supplied org-unit ids from the request DTO.
   * @return the managed org units to persist; never {@code null}, possibly empty.
   */
  private List<OrgUnit> resolveSubmittedOrgUnits(List<UUID> submittedOrgUnitIds) {
    if (submittedOrgUnitIds == null || submittedOrgUnitIds.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = submittedOrgUnitIds.stream().filter(Objects::nonNull).toList();
    if (ids.isEmpty()) {
      return List.of();
    }
    Map<UUID, OrgUnit> found = new HashMap<>();
    orgUnitRepository.findAllById(ids).forEach(unit -> found.put(unit.getId(), unit));
    return ids.stream().map(found::get).filter(Objects::nonNull).toList();
  }
}
