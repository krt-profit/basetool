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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.JobType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.catalogue.api.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.MemberPayoutSettings;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionUnit;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionSectionVersions.MissionSection;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
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
import org.jetbrains.annotations.Nullable;
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
 * Every write runs through {@link MissionEditor}: sign-up behind the read gate, a row edit behind
 * the participant gate, the party lead and the co-managers behind the manage gate.
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

  private final MemberPayoutSettings memberPayoutSettings;

  /** Repository used to resolve the desired/planned mission job type references. */
  private final JobTypeRepository jobTypeRepository;

  /** Resolves a registered user's org-unit memberships to stamp on the participant row. */
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  /** Polymorphic repository used to materialise org-unit affiliations by id. */
  private final OrgUnitRepository orgUnitRepository;

  /** Runs the load, gate, section check and audit of every roster edit. */
  private final MissionEditor missionEditor;

  /** Decides whether the caller may set the planned job type or rename an external row. */
  private final MissionAccessPolicy missionAccessPolicy;

  /** Resolves a free-text participant name to a registered member or an external name. */
  private final ParticipantTargetResolver participantTargetResolver;

  /** Publishes the mission notifications (REQ-MISSION-021…029). */
  private final MissionNotificationPublisher notificationPublisher;

  /**
   * Adds a participant with a user reference or guest name, an optional desired job type and
   * comment; delegates to the full overload without org units or payout choice.
   */
  @Transactional
  public Mission addParticipant(
      @NotNull UUID missionId,
      @Nullable UUID userId,
      @Nullable String guestName,
      @Nullable UUID desiredJobTypeId,
      @Nullable String comment) {
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
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when any referenced id
   *     is unknown
   */
  @Transactional
  public Mission addParticipant(
      @NotNull UUID missionId,
      @Nullable UUID userId,
      @Nullable String guestName,
      @Nullable UUID desiredJobTypeId,
      @Nullable String comment,
      @Nullable List<UUID> orgUnitIds,
      @Nullable PayoutPreference payoutPreference) {
    return missionEditor.edit(
        missionId,
        MissionGate.SEE,
        mission ->
            appendParticipant(
                mission,
                userId,
                guestName,
                desiredJobTypeId,
                comment,
                orgUnitIds,
                payoutPreference));
  }

  private MissionEdit appendParticipant(
      @NotNull Mission mission,
      @Nullable UUID userId,
      @Nullable String guestName,
      @Nullable UUID desiredJobTypeId,
      @Nullable String comment,
      @Nullable List<UUID> orgUnitIds,
      @Nullable PayoutPreference payoutPreference) {
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
      memberPayoutSettings.defaultFor(user.getId()).ifPresent(participant::setPayoutPreference);
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
    notificationPublisher.participantAdded(mission, participant);
    return MissionEdit.audited(
        AuditEventType.MISSION_PARTICIPANT_ADDED,
        finalUserId,
        AuditDetails.of("participant", participant.getId())
            .with("type", finalUserId != null ? "user" : "external"));
  }

  /**
   * Returns a participant of the given mission; a participant of another mission counts as not
   * found.
   *
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the participant
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
    return missionEditor.edit(
        missionId,
        MissionGate.participant(participantId),
        mission -> {
          mission.getParticipants().stream()
              .filter(p -> p.getId().equals(participantId))
              .findFirst()
              .ifPresent(leaving -> notificationPublisher.participantRemoved(mission, leaving));
          boolean removed =
              mission.getParticipants().removeIf(p -> p.getId().equals(participantId));
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
          return MissionEdit.audited(
              AuditEventType.MISSION_PARTICIPANT_REMOVED,
              AuditDetails.of("participant", participantId));
        });
  }

  /**
   * Updates a participant's per-mission attributes, guarded by the participant's own version; the
   * mission's version is not bumped.
   *
   * @param authentication the caller; a caller who may not manage the mission can neither set the
   *     planned job type nor rename the row onto a member or another guest
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the participant or
   *     any referenced id is unknown
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
    return missionEditor.edit(
        missionId,
        MissionGate.participant(participantId),
        mission ->
            applyAttributes(
                mission,
                participantId,
                new ParticipantAttributes(
                    desiredMissionJobTypeId,
                    plannedMissionJobTypeId,
                    comment,
                    startTime,
                    endTime,
                    orgUnitIds,
                    payoutPreference,
                    guestName),
                version,
                authentication));
  }

  /**
   * The editable per-mission attributes of one participant row.
   *
   * @param desiredMissionJobTypeId the desired mission job type, or {@code null} to clear it
   * @param plannedMissionJobTypeId the planned mission job type, or {@code null} to clear it
   * @param comment the free-text comment
   * @param startTime the credited start, or {@code null}
   * @param endTime the credited end, or {@code null}
   * @param orgUnitIds the declared affiliation of an external participant
   * @param payoutPreference the payout choice, or {@code null} to keep it
   * @param guestName the new external name, or {@code null} to keep it
   */
  private record ParticipantAttributes(
      UUID desiredMissionJobTypeId,
      UUID plannedMissionJobTypeId,
      String comment,
      Instant startTime,
      Instant endTime,
      List<UUID> orgUnitIds,
      PayoutPreference payoutPreference,
      String guestName) {}

  private MissionEdit applyAttributes(
      @NotNull Mission mission,
      @NotNull UUID participantId,
      @NotNull ParticipantAttributes attributes,
      Long version,
      Authentication authentication) {
    final UUID desiredMissionJobTypeId = attributes.desiredMissionJobTypeId();
    final UUID plannedMissionJobTypeId = attributes.plannedMissionJobTypeId();
    final Instant startTime = attributes.startTime();
    final Instant endTime = attributes.endTime();
    final PayoutPreference payoutPreference = attributes.payoutPreference();
    final String guestName = attributes.guestName();
    final String comment = attributes.comment();
    final List<UUID> orgUnitIds = attributes.orgUnitIds();

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
        missionAccessPolicy.canManageLoadedMission(mission, authentication);

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
    return MissionEdit.audited(
        AuditEventType.MISSION_PARTICIPANT_UPDATED,
        participant.getUser() != null ? participant.getUser().getId() : null,
        AuditDetails.of("participant", participantId));
  }

  /**
   * Marks a participant as checked in, setting {@code startTime} only on the first check-in so the
   * credited time cannot be shortened by a repeat; a repeat records nothing.
   *
   * @param missionId the mission id
   * @param participantId the participant to check in
   * @return the managed mission
   */
  @Transactional
  public Mission checkIn(UUID missionId, UUID participantId) {
    return missionEditor.edit(
        missionId,
        MissionGate.participant(participantId),
        mission -> {
          MissionParticipant participant = findParticipant(mission, participantId);
          if (mission.getActualStartTime() == null) {
            throw new IllegalArgumentException(
                "Cannot check in before mission actual start time is set");
          }
          if (participant.getStartTime() != null) {
            return MissionEdit.unchanged();
          }
          participant.setStartTime(Instant.now());
          missionParticipantRepository.saveAndFlush(participant);
          notificationPublisher.checkedIn(mission, participant);
          return MissionEdit.audited(
              AuditEventType.MISSION_PARTICIPANT_CHECKED_IN,
              participant.getUser() != null ? participant.getUser().getId() : null,
              AuditDetails.of("participant", participantId));
        });
  }

  /**
   * Marks a participant as checked out. Sets {@code endTime} to {@code now()}. Unlike check-in, a
   * repeated check-out overrides the previous timestamp — late check-out corrections.
   *
   * @param missionId the mission id
   * @param participantId the participant to check out
   * @return the managed mission
   */
  @Transactional
  public Mission checkOut(UUID missionId, UUID participantId) {
    return missionEditor.edit(
        missionId,
        MissionGate.participant(participantId),
        mission -> {
          MissionParticipant participant = findParticipant(mission, participantId);
          if (mission.getActualEndTime() != null
              && Instant.now().isAfter(mission.getActualEndTime())) {
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
          return MissionEdit.audited(
              AuditEventType.MISSION_PARTICIPANT_CHECKED_OUT,
              participant.getUser() != null ? participant.getUser().getId() : null,
              AuditDetails.of("participant", participantId));
        });
  }

  /**
   * Updates a participant's payout preference (PAYOUT or DONATE); a {@code null} preference changes
   * and records nothing.
   *
   * @param missionId the mission id
   * @param participantId the participant whose preference changes
   * @param preference the new preference, or {@code null}
   * @return the managed mission
   */
  @Transactional
  public Mission updatePayoutPreference(
      UUID missionId, UUID participantId, PayoutPreference preference) {
    return missionEditor.edit(
        missionId,
        MissionGate.participant(participantId),
        mission -> {
          MissionParticipant participant = findParticipant(mission, participantId);
          if (preference == null) {
            return MissionEdit.unchanged();
          }
          participant.setPayoutPreference(preference);
          missionParticipantRepository.save(participant);
          missionParticipantRepository.flush();
          return MissionEdit.audited(
              AuditEventType.MISSION_PARTICIPANT_UPDATED,
              participant.getUser() != null ? participant.getUser().getId() : null,
              AuditDetails.of("participant", participantId).with("field", "payoutPreference"));
        });
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
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the mission or the
   *     referenced user is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedPartyLeadVersion} is stale
   */
  @Transactional
  public Mission setPartyLead(
      @NotNull UUID missionId,
      @Nullable UUID userId,
      @Nullable String guestName,
      @NotNull Long expectedPartyLeadVersion) {
    return missionEditor.editSection(
        missionId,
        MissionGate.MANAGE,
        MissionSection.PARTY_LEAD,
        expectedPartyLeadVersion,
        mission -> {
          if (userId != null) {
            User user = Entities.require(userRepository.findPlainById(userId), "User not found");
            boolean newLead =
                mission.getPartyLeadUser() == null
                    || !userId.equals(mission.getPartyLeadUser().getId());
            mission.setPartyLeadUser(user);
            mission.setPartyLeadGuestName(null);
            if (newLead) {
              notificationPublisher.responsibilityAssigned(mission, userId, "PARTY_LEAD");
            }
          } else if (guestName != null && !guestName.isBlank()) {
            mission.setPartyLeadUser(null);
            mission.setPartyLeadGuestName(guestName.trim());
          } else {
            mission.setPartyLeadUser(null);
            mission.setPartyLeadGuestName(null);
          }
          return MissionEdit.audited(
              AuditEventType.MISSION_PARTY_LEAD_CHANGED,
              userId,
              AuditDetails.of(
                  "kind",
                  userId != null
                      ? "user"
                      : (guestName != null && !guestName.isBlank() ? "external" : "cleared")));
        });
  }

  /**
   * Adds a co-manager. Co-managers can edit the mission with the same privileges as the owner
   * (except they cannot transfer ownership — see {@link MissionAccessPolicy#canChangeOwner}).
   *
   * @param missionId the mission id
   * @param userId the user to add as co-manager
   * @return the managed mission
   */
  @Transactional
  public Mission addManager(@NotNull UUID missionId, @NotNull UUID userId) {
    return missionEditor.edit(
        missionId,
        MissionGate.MANAGE,
        mission -> {
          User user = Entities.require(userRepository.findPlainById(userId), "User not found");
          if (mission.getManagers().add(user)) {
            notificationPublisher.responsibilityAssigned(mission, userId, "MANAGER");
          }
          return MissionEdit.audited(AuditEventType.MISSION_MANAGER_ADDED, userId, null);
        });
  }

  /**
   * Removes a co-manager. The owner cannot be removed via this method — use {@link
   * MissionService#updateMissionOwner} to transfer ownership first.
   *
   * @param missionId the mission id
   * @param userId the co-manager to remove
   * @return the managed mission
   */
  @Transactional
  public Mission removeManager(@NotNull UUID missionId, @NotNull UUID userId) {
    return missionEditor.edit(
        missionId,
        MissionGate.MANAGE,
        mission -> {
          mission.getManagers().removeIf(u -> u.getId().equals(userId));
          return MissionEdit.audited(AuditEventType.MISSION_MANAGER_REMOVED, userId, null);
        });
  }

  private static MissionParticipant findParticipant(
      @NotNull Mission mission, @NotNull UUID participantId) {
    return Entities.require(
        mission.getParticipants().stream().filter(p -> p.getId().equals(participantId)).findFirst(),
        "Participant not found in mission");
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
  private List<OrgUnit> resolveSubmittedOrgUnits(@Nullable List<UUID> submittedOrgUnitIds) {
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
