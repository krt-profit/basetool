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

import static de.greluc.krt.profit.basetool.backend.support.MissionSectionVersions.bumpSectionVersion;
import static de.greluc.krt.profit.basetool.backend.support.MissionSectionVersions.enforceSectionVersion;

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionFrequency;
import de.greluc.krt.profit.basetool.backend.model.MissionObjectiveKind;
import de.greluc.krt.profit.basetool.backend.model.MissionOwnership;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.Ship;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.MissionParticipantCount;
import de.greluc.krt.profit.basetool.backend.repository.FrequencyTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFrequencyRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionOwnershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
import de.greluc.krt.profit.basetool.backend.support.MissionSectionVersions.MissionSection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the {@code mission} aggregate: roster, units and crews, schedule, frequencies, goals and
 * steps, managers and owner.
 *
 * <p>Writes are guarded by the mission's {@code @Version} or a dedicated section counter;
 * participant, unit and crew writes use dirty-checking on the managed aggregate, and ownership is
 * versioned separately in {@code mission_ownership}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionService {

  /** Hard cap on participants per mission — see {@link #addParticipant} for the rationale. */
  public static final int MAX_PARTICIPANTS_PER_MISSION = 500;

  private final MissionRepository missionRepository;
  private final MissionParticipantRepository missionParticipantRepository;
  private final UserRepository userRepository;
  private final FrequencyTypeRepository frequencyTypeRepository;
  private final MissionFrequencyRepository missionFrequencyRepository;
  private final MissionOwnershipRepository missionOwnershipRepository;
  private final OperationRepository operationRepository;
  private final UserService userService;
  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelperService;
  private final AuditService auditService;
  private final MissionTimelineService missionTimelineService;
  private final MissionParticipantService missionParticipantService;
  private final MissionStructureService missionStructureService;

  /**
   * Always throws; listing missions must go through {@link #searchMissions(String, Instant,
   * Instant, List, Boolean, UUID, Pageable)} so visibility scoping applies.
   *
   * @param pageable unused
   * @return never; always throws
   * @throws UnsupportedOperationException always
   * @deprecated use {@link #searchMissions} with appropriate filters.
   */
  @Deprecated(forRemoval = true)
  public Page<Mission> getAllMissions(@NotNull Pageable pageable) {
    throw new UnsupportedOperationException(
        "getAllMissions bypasses the multi-squadron visibility filter and must not be used; "
            + "call searchMissions with the appropriate isInternal / scopeSquadronId filters "
            + "instead (MULTI_SQUADRON_PLAN.md §1, audit finding M-5).");
  }

  /**
   * Returns the missions for the Lager mission pickers: all visible {@code PLANNED} / {@code
   * ACTIVE} missions plus {@code COMPLETED} / {@code CANCELLED} ones planned within the last three
   * months, scoped via {@link OwnerScopeService#currentScopePredicate()}.
   *
   * @return lightweight reference projection of the picker-visible missions for the caller
   */
  public List<MissionReferenceDto> findAllActiveReference() {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    Instant terminalCutoff = OffsetDateTime.now(ZoneOffset.UTC).minusMonths(3).toInstant();
    return missionRepository.findAllActiveReference(
        scope.adminAllScope(),
        scope.activeOrgUnitId(),
        scope.memberOrgUnitIds(),
        authHelperService.isMemberOrAbove(),
        terminalCutoff);
  }

  /**
   * Counts the registered participants of the given missions in one grouped query.
   *
   * @param missionIds the missions on the page; an empty collection runs no query.
   * @return count per mission id; missions with no participants are absent rather than zero.
   */
  @Transactional(readOnly = true)
  public Map<UUID, Long> registeredCounts(@NotNull Collection<UUID> missionIds) {
    if (missionIds.isEmpty()) {
      return Map.of();
    }
    return missionParticipantRepository.countByMissions(missionIds).stream()
        .collect(
            Collectors.toMap(
                MissionParticipantCount::missionId, MissionParticipantCount::registered));
  }

  /**
   * Free-text search over mission name, description, location and operation name. Optional filters
   * narrow by status, time window and operation. Used by the mission list page.
   *
   * @return paged matching missions
   */
  public Page<Mission> searchMissions(
      String query,
      Instant start,
      Instant end,
      List<String> status,
      Boolean isInternal,
      UUID operationId,
      @NotNull Pageable pageable) {
    if (status == null || status.isEmpty()) {
      status = List.of("PLANNED", "ACTIVE", "COMPLETED", "CANCELLED");
    }
    Boolean effectiveIsInternal = isInternal;
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return missionRepository.searchMissions(
        LikePatterns.escapeNullable(query),
        start,
        end,
        status,
        effectiveIsInternal,
        operationId,
        scope.adminAllScope(),
        scope.activeOrgUnitId(),
        scope.memberOrgUnitIds(),
        authHelperService.isMemberOrAbove(),
        pageable);
  }

  /**
   * Returns the mission with the participant and unit graphs its detail DTO reads already loaded.
   *
   * @param id mission primary key
   * @return the mission
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  public Mission getMissionById(@NotNull UUID id) {
    Mission mission = Entities.require(missionRepository.findById(id), "Mission not found");
    missionRepository.fetchAssignedUnitGraph(id);
    return mission;
  }

  /**
   * Statuses a mission may carry to qualify as the home-page "next mission". Only live operational
   * missions ({@code PLANNED} / {@code ACTIVE}) are eligible — a {@code COMPLETED} or {@code
   * CANCELLED} mission with a future planned start must never surface in the banner.
   */
  private static final List<String> NEXT_MISSION_STATUSES = List.of("PLANNED", "ACTIVE");

  /**
   * Returns the next {@code PLANNED} or {@code ACTIVE} mission by planned start for the home-page
   * banner. With an org-unit scope only that scope's missions count (REQ-MISSION-008); without one,
   * the organisation-wide next mission is returned.
   *
   * @return the next mission, or empty when none upcoming
   */
  public Optional<Mission> getNextMission() {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    Instant now = Instant.now();
    Optional<Mission> next;
    if (scope.adminAllScope()
        || (scope.activeOrgUnitId() == null && scope.memberOrgUnitIds().isEmpty())) {
      next = findNextMissionHead(now);
    } else {
      next = findNextScopedMissionHead(now, scope);
    }
    return next.map(Mission::getId).flatMap(missionRepository::findById);
  }

  /**
   * Fetches the unscoped next-mission head in {@link #NEXT_MISSION_STATUSES}.
   *
   * @param now exclusive lower bound on {@code plannedStartTime}
   * @return the next-mission head; the caller re-fetches it with its graph
   */
  private Optional<Mission> findNextMissionHead(Instant now) {
    return missionRepository.findFirstByPlannedStartTimeAfterAndStatusInOrderByPlannedStartTimeAsc(
        now, NEXT_MISSION_STATUSES);
  }

  /**
   * Fetches the next-mission head within the caller's org-unit scope (REQ-MISSION-008).
   *
   * @param now exclusive lower bound on {@code plannedStartTime}
   * @param scope the caller's effective, non-empty org-unit scope
   * @return the next-mission head; the caller re-fetches it with its graph
   */
  private Optional<Mission> findNextScopedMissionHead(Instant now, @NotNull ScopePredicate scope) {
    return missionRepository
        .findNextScopedMission(
            now,
            NEXT_MISSION_STATUSES,
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            PageRequest.of(0, 1))
        .stream()
        .findFirst();
  }

  /**
   * Persists a new mission; all server-managed fields are set here, the request carries only
   * caller-controllable ones.
   *
   * @param request create payload (already validated by Bean Validation at the controller boundary)
   * @return the persisted mission entity
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when {@code
   *     operationId} does not resolve
   */
  @Transactional
  public Mission createMission(CreateMissionRequest request) {
    Mission mission = new Mission();
    applyCreatePayload(mission, request);

    validateMissionTimes(mission);

    userService.getCurrentUser().ifPresent(mission::setOwner);

    if (mission.getOwner() != null) {
      mission.setOwningOrgUnit(
          ownerScopeService.resolveOrgUnitForPickerOutputNullable(
              mission.getOwner(), request.owningOrgUnitId()));
    } else {
      ownerScopeService.currentOrgUnit().ifPresent(mission::setOwningOrgUnit);
    }

    Mission saved = missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_CREATED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("status", mission.getStatus()));
    applyCreateTimeline(saved, request);
    return saved;
  }

  /**
   * Seeds the optional goals and steps of the create request onto the new mission in submitted
   * order, audited and without a section-version check.
   *
   * @param mission the managed, already-persisted mission
   * @param request the validated create payload with optional {@code objectives}/{@code steps}
   */
  private void applyCreateTimeline(Mission mission, CreateMissionRequest request) {
    if (request.objectives() != null) {
      int index = 0;
      for (CreateMissionRequest.NewObjective objective : request.objectives()) {
        missionTimelineService.addObjectiveAtCreate(
            mission, objective.title(), objective.kind(), index++);
      }
    }
    if (request.steps() != null) {
      int index = 0;
      for (CreateMissionRequest.NewStep step : request.steps()) {
        missionTimelineService.addStepAtCreate(mission, step.title(), step.meta(), index++);
      }
    }
  }

  /**
   * Copies the caller-controllable fields of {@link CreateMissionRequest} onto a new {@link
   * Mission}, resolving the operation.
   *
   * @param mission target entity (caller owns its identity and version)
   * @param request validated create payload
   */
  private void applyCreatePayload(@NotNull Mission mission, @NotNull CreateMissionRequest request) {
    mission.setName(request.name());
    mission.setDescription(request.description());
    mission.setMeetingPoint(request.meetingPoint());
    mission.setCalendarLink(request.calendarLink());
    mission.setStatus(request.status());
    mission.setMeetingTime(request.meetingTime());
    mission.setPlannedStartTime(request.plannedStartTime());
    mission.setPlannedEndTime(request.plannedEndTime());
    mission.setIsInternal(request.isInternal() != null ? request.isInternal() : Boolean.FALSE);

    if (request.operationId() != null) {
      Operation op =
          Entities.require(
              operationRepository.findById(request.operationId()), "Operation not found");
      mission.setOperation(op);
    } else {
      mission.setOperation(null);
    }
  }

  /**
   * Fully updates a mission's metadata and shallow references; participants, units and crews are
   * untouched. Bumps all three section counters, so prefer the section-scoped updates.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the mission or
   *     any referenced id is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     version is stale
   */
  @Transactional
  public Mission updateMission(@NotNull UUID missionId, UpdateMissionRequest request) {
    Mission mission =
        Entities.require(missionRepository.findByIdForFullReplace(missionId), "Mission not found");

    if (!mission.getVersion().equals(request.version())) {
      throw new ObjectOptimisticLockingFailureException(Mission.class, missionId);
    }

    Instant explicitActualStart = request.actualStartTime();
    boolean autoStampActualStart =
        "ACTIVE".equals(request.status())
            && !"ACTIVE".equals(mission.getStatus())
            && explicitActualStart == null;
    Instant effectiveActualStart = autoStampActualStart ? Instant.now() : explicitActualStart;

    mission.setName(request.name());
    mission.setDescription(request.description());
    mission.setMeetingPoint(request.meetingPoint());
    mission.setCalendarLink(request.calendarLink());
    mission.setStatus(request.status());
    mission.setMeetingTime(request.meetingTime());
    mission.setPlannedStartTime(request.plannedStartTime());
    mission.setPlannedEndTime(request.plannedEndTime());
    mission.setActualStartTime(effectiveActualStart);

    if (request.operationId() != null) {
      Operation op =
          Entities.require(
              operationRepository.findById(request.operationId()), "Operation not found");
      mission.setOperation(op);
    } else {
      mission.setOperation(null);
    }

    mission.setIsInternal(request.isInternal() != null ? request.isInternal() : Boolean.FALSE);

    Instant newEndTime = request.actualEndTime();
    mission.setActualEndTime(newEndTime);

    if (newEndTime != null) {
      for (MissionParticipant participant : mission.getParticipants()) {
        if (participant.getStartTime() != null) {
          if (participant.getEndTime() == null || participant.getEndTime().isAfter(newEndTime)) {
            participant.setEndTime(newEndTime);
          }
        }
      }
    }

    validateMissionTimes(mission);

    bumpSectionVersion(mission, MissionSection.CORE);
    bumpSectionVersion(mission, MissionSection.SCHEDULE);
    bumpSectionVersion(mission, MissionSection.FLAGS);

    Mission saved = missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_UPDATED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("section", "full").with("status", mission.getStatus()));
    return saved;
  }

  /**
   * Updates the core section of a mission, guarded by {@code coreVersion}.
   *
   * <p>A transition to {@code ACTIVE} without an actual start time also stamps {@code
   * actualStartTime} and bumps {@code scheduleVersion}.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when any referenced
   *     id is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     {@code expectedCoreVersion} is stale
   */
  @Transactional
  public Mission updateCoreSection(
      @NotNull UUID missionId,
      @NotNull String name,
      String description,
      String calendarLink,
      String status,
      UUID operationId,
      String meetingPoint,
      @NotNull Long expectedCoreVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository, mission, MissionSection.CORE, expectedCoreVersion, missionId);

    if ("ACTIVE".equals(status)
        && !"ACTIVE".equals(mission.getStatus())
        && mission.getActualStartTime() == null) {
      bumpActualStartTimeOnActivationWithinTransaction(mission);
    }

    mission.setName(name);
    mission.setDescription(description);
    mission.setMeetingPoint(meetingPoint);
    mission.setCalendarLink(calendarLink);
    if (status != null) {
      mission.setStatus(status);
    }

    if (operationId != null) {
      Operation op =
          Entities.require(operationRepository.findById(operationId), "Operation not found");
      mission.setOperation(op);
    } else {
      mission.setOperation(null);
    }

    Mission saved = missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_UPDATED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("section", "core").with("status", mission.getStatus()));
    return saved;
  }

  /**
   * Updates the schedule section of a mission (UTC), guarded by {@code scheduleVersion}. Setting
   * {@code actualEndTime} also closes open participant end times.
   *
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     {@code expectedScheduleVersion} is stale
   */
  @Transactional
  public Mission updateScheduleSection(
      @NotNull UUID missionId,
      Instant meetingTime,
      Instant plannedStartTime,
      Instant plannedEndTime,
      Instant actualStartTime,
      Instant actualEndTime,
      @NotNull Long expectedScheduleVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository, mission, MissionSection.SCHEDULE, expectedScheduleVersion, missionId);
    mission.setMeetingTime(meetingTime);
    mission.setPlannedStartTime(plannedStartTime);
    mission.setPlannedEndTime(plannedEndTime);
    mission.setActualStartTime(actualStartTime);
    mission.setActualEndTime(actualEndTime);

    validateMissionTimes(mission);
    Mission saved = missionRepository.save(mission);

    if (actualEndTime != null) {
      missionParticipantRepository.clampCheckedInEndTimes(missionId, actualEndTime);
    }
    auditService.record(
        AuditEventType.MISSION_UPDATED,
        mission.getId(),
        mission.getName(),
        null,
        "section=schedule");
    return saved;
  }

  /**
   * Updates only the flags section of a mission ({@code isInternal}). Validates the dedicated
   * {@code flagsVersion} counter.
   *
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     {@code expectedFlagsVersion} is stale
   */
  @Transactional
  public Mission updateFlagsSection(
      @NotNull UUID missionId, @NotNull Boolean isInternal, @NotNull Long expectedFlagsVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository, mission, MissionSection.FLAGS, expectedFlagsVersion, missionId);
    mission.setIsInternal(isInternal);
    Mission saved = missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_UPDATED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("section", "flags").with("isInternal", isInternal));
    return saved;
  }

  /**
   * Stamps {@code actualStartTime} on activation and bumps the schedule counter, via dirty-checking
   * on the managed entity.
   *
   * @param mission managed mission entity in the current transaction
   */
  private void bumpActualStartTimeOnActivationWithinTransaction(@NotNull Mission mission) {
    mission.setActualStartTime(Instant.now());
    bumpSectionVersion(mission, MissionSection.SCHEDULE);
  }

  /**
   * Deletes a mission with its participants, finance entries, units, crews and frequencies; linked
   * inventory items and refinery orders are unlinked, not deleted.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the mission is
   *     unknown
   */
  @Transactional
  public void deleteMission(@NotNull UUID missionId) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    final UUID deletedMissionId = mission.getId();
    final String deletedMissionName = mission.getName();

    if (mission.getRefineryOrders() != null) {
      mission.getRefineryOrders().forEach(order -> order.setMission(null));
      mission.getRefineryOrders().clear();
    }

    if (mission.getSubMissions() != null) {
      mission.getSubMissions().forEach(sub -> sub.setParent(null));
      mission.getSubMissions().clear();
    }

    missionRepository.delete(mission);
    auditService.record(
        AuditEventType.MISSION_DELETED, deletedMissionId, deletedMissionName, null, null);
  }

  private void validateMissionTimes(Mission mission) {
    if (mission.getMeetingTime() != null && mission.getPlannedStartTime() != null) {
      if (mission.getMeetingTime().isAfter(mission.getPlannedStartTime())) {
        throw new IllegalArgumentException("Meeting time cannot be later than planned start time");
      }
    }
    if (mission.getPlannedStartTime() != null && mission.getPlannedEndTime() != null) {
      if (mission.getPlannedStartTime().isAfter(mission.getPlannedEndTime())) {
        throw new IllegalArgumentException(
            "Planned start time cannot be later than planned end time");
      }
    }
  }

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
    return missionParticipantService.addParticipant(
        missionId, userId, guestName, desiredJobTypeId, comment);
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
    return missionParticipantService.addParticipant(
        missionId, userId, guestName, desiredJobTypeId, comment, orgUnitIds, payoutPreference);
  }

  /**
   * Returns a participant of the given mission; a participant of another mission counts as not
   * found.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the participant
   *     does not exist on this mission
   */
  public MissionParticipant getParticipant(@NotNull UUID missionId, @NotNull UUID participantId) {
    return missionParticipantService.getParticipant(missionId, participantId);
  }

  /**
   * Returns the participants of a mission not yet assigned to any unit or crew.
   *
   * @param missionId mission id
   * @return list of unassigned participants
   */
  public List<MissionParticipant> getUnassignedParticipants(@NotNull UUID missionId) {
    return missionParticipantService.getUnassignedParticipants(missionId);
  }

  /**
   * Removes a participant from a mission and the participant's linked finance entries. The
   * participant row itself is deleted (not soft-deleted); finance entries with FK to the
   * participant cascade-delete to keep the FK constraint happy.
   */
  @Transactional
  public Mission removeParticipant(@NotNull UUID missionId, @NotNull UUID participantId) {
    return missionParticipantService.removeParticipant(missionId, participantId);
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
    return missionParticipantService.updateParticipantAttributes(
        missionId,
        participantId,
        desiredMissionJobTypeId,
        plannedMissionJobTypeId,
        comment,
        startTime,
        endTime,
        orgUnitIds,
        payoutPreference,
        guestName,
        version,
        authentication);
  }

  /**
   * Marks a participant as checked in. Sets {@code startTime} to {@code now()} only if the
   * participant has not been checked in before — repeated check-in is a no-op so the original
   * arrival time is preserved.
   */
  @Transactional
  public Mission checkIn(UUID missionId, UUID participantId) {
    return missionParticipantService.checkIn(missionId, participantId);
  }

  /**
   * Marks a participant as checked out. Sets {@code endTime} to {@code now()}. Unlike check-in, a
   * repeated check-out overrides the previous timestamp — late check-out corrections.
   */
  @Transactional
  public Mission checkOut(UUID missionId, UUID participantId) {
    return missionParticipantService.checkOut(missionId, participantId);
  }

  /**
   * Updates a participant's payout preference (PAYOUT or DONATE). Guests can change their own
   * preference even without authentication — the security gate on the participant row keeps other
   * users' preferences locked.
   */
  @Transactional
  public Mission updatePayoutPreference(
      UUID missionId, UUID participantId, PayoutPreference preference) {
    return missionParticipantService.updatePayoutPreference(missionId, participantId, preference);
  }

  /**
   * Adds a unit to a mission. A blank {@code name} is derived from the ship or ship type; at least
   * one of name, ship and ship type is required.
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
    return missionStructureService.addUnitToMission(
        missionId, name, shipTypeId, shipId, highValueUnit, frequency, responsibleUserId, note);
  }

  /** Updates a unit's name and assigned ship, guarded by the unit's {@code expectedVersion}. */
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
    return missionStructureService.updateMissionUnit(
        missionId,
        unitId,
        expectedVersion,
        name,
        shipTypeId,
        shipId,
        highValueUnit,
        frequency,
        responsibleUserId,
        note);
  }

  /**
   * Collects the ships selectable for this mission's units: ships of registered participants,
   * regardless of org unit, plus ships already assigned to a unit; deduplicated, participant ships
   * first.
   *
   * @param missionId the mission whose selectable unit ships are collected, never {@code null}
   * @return the candidate ships for this mission's unit ship pickers
   * @throws NotFoundException when the mission id does not resolve
   */
  @Transactional(readOnly = true)
  public List<Ship> getSelectableUnitShips(@NotNull UUID missionId) {
    return missionStructureService.getSelectableUnitShips(missionId);
  }

  /** Removes a unit; its participants stay on the mission with unit and crew cleared. */
  @Transactional
  public Mission removeMissionUnit(@NotNull UUID missionId, @NotNull UUID unitId) {
    return missionStructureService.removeMissionUnit(missionId, unitId);
  }

  /**
   * Appends a not-done step to the mission's Ablauf, guarded by {@code stepsVersion}.
   *
   * @param missionId the mission id
   * @param title the required step title
   * @param meta the optional free-text time/place hint
   * @param expectedStepsVersion the steps-section version the caller last saw
   * @return the managed mission
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the mission is
   *     unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedStepsVersion} is stale
   */
  @Transactional
  public Mission addStep(
      @NotNull UUID missionId, String title, String meta, @NotNull Long expectedStepsVersion) {
    return missionTimelineService.addStep(missionId, title, meta, expectedStepsVersion);
  }

  /**
   * Edits an existing Ablauf step's title and time/place hint. Mutates the managed child via
   * dirty-checking (no explicit child save) and bumps {@code stepsVersion}.
   *
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedStepsVersion} is stale
   */
  @Transactional
  public Mission updateStep(
      @NotNull UUID missionId,
      @NotNull UUID stepId,
      String title,
      String meta,
      @NotNull Long expectedStepsVersion) {
    return missionTimelineService.updateStep(missionId, stepId, title, meta, expectedStepsVersion);
  }

  /**
   * Removes an Ablauf step and re-packs the remaining steps' {@code orderIndex} to 0..n-1 so the
   * timeline stays contiguous. Bumps {@code stepsVersion}.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the step is not
   *     a child of the mission
   */
  @Transactional
  public Mission deleteStep(
      @NotNull UUID missionId, @NotNull UUID stepId, @NotNull Long expectedStepsVersion) {
    return missionTimelineService.deleteStep(missionId, stepId, expectedStepsVersion);
  }

  /**
   * Reorders the mission's steps; {@code orderedStepIds} must be exactly its step ids. Guarded by
   * {@code stepsVersion}.
   *
   * @throws IllegalArgumentException when the id set does not match the mission's steps exactly
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedStepsVersion} is stale
   */
  @Transactional
  public Mission reorderSteps(
      @NotNull UUID missionId,
      @NotNull List<UUID> orderedStepIds,
      @NotNull Long expectedStepsVersion) {
    return missionTimelineService.reorderSteps(missionId, orderedStepIds, expectedStepsVersion);
  }

  /**
   * Sets a step's {@code done} flag and bumps {@code stepsVersion}.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the step is not
   *     a child of the mission
   */
  @Transactional
  public Mission toggleStepDone(
      @NotNull UUID missionId,
      @NotNull UUID stepId,
      boolean done,
      @NotNull Long expectedStepsVersion) {
    return missionTimelineService.toggleStepDone(missionId, stepId, done, expectedStepsVersion);
  }

  /**
   * Appends a goal to the mission, guarded by {@code objectivesVersion}; the audit event carries no
   * title.
   *
   * @param missionId the mission id
   * @param title the required goal text
   * @param kind the classification (primary / secondary / non-goal)
   * @param expectedObjectivesVersion the goals-section version the caller last saw
   * @return the managed mission
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the mission is
   *     unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedObjectivesVersion} is stale
   */
  @Transactional
  public Mission addObjective(
      @NotNull UUID missionId,
      String title,
      @NotNull MissionObjectiveKind kind,
      @NotNull Long expectedObjectivesVersion) {
    return missionTimelineService.addObjective(missionId, title, kind, expectedObjectivesVersion);
  }

  /**
   * Edits an existing goal's text and classification. Mutates the managed child via dirty-checking
   * (no explicit child save) and bumps {@code objectivesVersion}.
   *
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedObjectivesVersion} is stale
   */
  @Transactional
  public Mission updateObjective(
      @NotNull UUID missionId,
      @NotNull UUID objectiveId,
      String title,
      @NotNull MissionObjectiveKind kind,
      @NotNull Long expectedObjectivesVersion) {
    return missionTimelineService.updateObjective(
        missionId, objectiveId, title, kind, expectedObjectivesVersion);
  }

  /**
   * Removes a goal and re-packs the remaining goals' {@code orderIndex} to 0..n-1 so the list stays
   * contiguous. Bumps {@code objectivesVersion}.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the goal is not
   *     a child of the mission
   */
  @Transactional
  public Mission deleteObjective(
      @NotNull UUID missionId, @NotNull UUID objectiveId, @NotNull Long expectedObjectivesVersion) {
    return missionTimelineService.deleteObjective(
        missionId, objectiveId, expectedObjectivesVersion);
  }

  /**
   * Reorders the mission's goals; {@code orderedObjectiveIds} must be exactly its goal ids. Guarded
   * by {@code objectivesVersion}.
   *
   * @throws IllegalArgumentException when the id set does not match the mission's goals exactly
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedObjectivesVersion} is stale
   */
  @Transactional
  public Mission reorderObjectives(
      @NotNull UUID missionId,
      @NotNull List<UUID> orderedObjectiveIds,
      @NotNull Long expectedObjectivesVersion) {
    return missionTimelineService.reorderObjectives(
        missionId, orderedObjectiveIds, expectedObjectivesVersion);
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
    return missionStructureService.addCrewToShip(
        missionId, missionUnitId, participantId, jobTypeIds);
  }

  /** Updates a crew's assigned job types, guarded by the crew's {@code expectedVersion}. */
  @Transactional
  public Mission updateCrewInShip(
      @NotNull UUID missionId,
      @NotNull UUID missionUnitId,
      @NotNull UUID crewId,
      Long expectedVersion,
      @NotNull Set<UUID> jobTypeIds) {
    return missionStructureService.updateCrewInShip(
        missionId, missionUnitId, crewId, expectedVersion, jobTypeIds);
  }

  /**
   * Removes a crew. Participants assigned to the crew fall back to the parent unit's unassigned
   * slot.
   */
  @Transactional
  public Mission removeCrewFromShip(
      @NotNull UUID missionId, @NotNull UUID missionUnitId, @NotNull UUID crewId) {
    return missionStructureService.removeCrewFromShip(missionId, missionUnitId, crewId);
  }

  /**
   * Creates a sub-mission under a parent mission; {@code parent} and the owning squadron come from
   * the parent.
   *
   * @param parentMissionId path-resolved parent id (authoritative)
   * @param request create payload (validated at the controller boundary)
   * @return the persisted sub-mission entity
   * @throws NotFoundException when {@code parentMissionId} or {@code operationId} does not resolve
   */
  @Transactional
  public Mission addSubMission(@NotNull UUID parentMissionId, CreateMissionRequest request) {
    Mission parent =
        Entities.require(missionRepository.findById(parentMissionId), "Parent mission not found");

    Mission subMission = new Mission();
    applyCreatePayload(subMission, request);
    subMission.setParent(parent);
    subMission.setOwningOrgUnit(parent.getOwningOrgUnit());

    validateMissionTimes(subMission);
    Mission saved = missionRepository.save(subMission);
    auditService.record(
        AuditEventType.MISSION_CREATED,
        subMission.getId(),
        subMission.getName(),
        null,
        AuditDetails.of("parent", parentMissionId));
    return saved;
  }

  /**
   * Creates or updates a mission's typed radio frequency with an atomic upsert; last writer wins.
   *
   * @param missionId the mission to set the channel on.
   * @param frequencyTypeId the typed channel.
   * @param value the frequency value (range-validated at the boundary).
   * @return the managed mission, re-fetched so its frequencies include the upserted row.
   * @throws NotFoundException when the mission or the frequency type is unknown.
   */
  @Transactional
  public Mission addOrUpdateMissionFrequency(
      @NotNull UUID missionId, @NotNull UUID frequencyTypeId, @NotNull BigDecimal value) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    String missionName = mission.getName();

    if (!frequencyTypeRepository.existsById(frequencyTypeId)) {
      throw new NotFoundException("FrequencyType not found");
    }

    missionFrequencyRepository.upsertTypedFrequency(missionId, frequencyTypeId, value);

    auditService.record(
        AuditEventType.MISSION_FREQUENCY_CHANGED,
        missionId,
        missionName,
        null,
        AuditDetails.of("frequencyType", frequencyTypeId));
    return Entities.require(missionRepository.findById(missionId), "Mission not found");
  }

  /**
   * Adds a custom radio frequency with a free-text label to a mission (REQ-MISSION-014); no mission
   * version is bumped.
   *
   * @param missionId the mission to attach the channel to.
   * @param name the channel label (non-blank, ≤ 100 chars).
   * @param value the frequency value (0 – 999.99).
   * @return the managed mission with the new custom frequency attached.
   * @throws NotFoundException when the mission is unknown.
   */
  @Transactional
  public Mission addCustomMissionFrequency(
      @NotNull UUID missionId, @NotNull String name, @NotNull BigDecimal value) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    MissionFrequency freq = new MissionFrequency();
    freq.setMission(mission);
    freq.setName(name.trim());
    freq.setValue(value);
    mission.getFrequencies().add(freq);
    MissionFrequency saved = missionFrequencyRepository.save(freq);

    auditService.record(
        AuditEventType.MISSION_FREQUENCY_CHANGED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("custom", saved.getId()));
    return mission;
  }

  /**
   * Updates a custom frequency's label and value (REQ-MISSION-014), guarded by the row's own
   * version.
   *
   * @param missionId the owning mission id.
   * @param frequencyId the custom frequency row id.
   * @param name the new channel label.
   * @param value the new frequency value.
   * @param expectedVersion the optimistic-lock version the caller echoed back.
   * @return the managed mission with the updated custom frequency.
   * @throws NotFoundException when the mission or a matching custom frequency is unknown.
   * @throws IllegalArgumentException when the target row is a typed (global) frequency.
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     version is stale.
   */
  @Transactional
  public Mission updateCustomMissionFrequency(
      @NotNull UUID missionId,
      @NotNull UUID frequencyId,
      @NotNull String name,
      @NotNull BigDecimal value,
      @NotNull Long expectedVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    MissionFrequency freq =
        Entities.require(
            mission.getFrequencies().stream()
                .filter(f -> f.getId() != null && f.getId().equals(frequencyId))
                .findFirst(),
            "Frequency not found in this mission");

    if (freq.getFrequencyType() != null) {
      throw new IllegalArgumentException(
          "Cannot edit a typed frequency through the custom-frequency endpoint");
    }

    long current = freq.getVersion() == null ? 0L : freq.getVersion();
    if (!expectedVersion.equals(current)) {
      throw new ObjectOptimisticLockingFailureException(MissionFrequency.class, frequencyId);
    }

    freq.setName(name.trim());
    freq.setValue(value);
    missionFrequencyRepository.save(freq);

    auditService.record(
        AuditEventType.MISSION_FREQUENCY_CHANGED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("custom", frequencyId));
    return mission;
  }

  /** Removes a frequency entry from a mission. */
  @Transactional
  public Mission removeMissionFrequency(@NotNull UUID missionId, @NotNull UUID frequencyId) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    boolean removed =
        mission.getFrequencies().removeIf(f -> f.getId() != null && f.getId().equals(frequencyId));
    if (!removed) {
      throw new NotFoundException("Frequency not found in this mission");
    }

    auditService.record(
        AuditEventType.MISSION_FREQUENCY_REMOVED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("frequency", frequencyId));
    return mission;
  }

  /**
   * Changes a mission's owner, guarded by {@link Mission#getOwnershipVersion()} ({@code 0} while
   * never changed); {@code Mission.version} is not bumped and the previous owner does not become a
   * co-manager.
   *
   * @param missionId the mission to hand over
   * @param userId the new owner
   * @param expectedOwnershipVersion the ownership version the caller last read
   * @return the managed mission with the new owner and bumped ownership version
   * @throws NotFoundException when the mission or the user does not exist
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the echo is stale
   */
  @Transactional
  public Mission updateMissionOwner(
      @NotNull UUID missionId, @NotNull UUID userId, @NotNull Long expectedOwnershipVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    User user = Entities.require(userRepository.findPlainById(userId), "User not found");
    long ownershipVersion = upsertMissionOwnership(mission, user, expectedOwnershipVersion);
    mission.setOwner(user);
    mission.setOwnershipVersion(ownershipVersion);
    auditService.record(
        AuditEventType.MISSION_OWNER_CHANGED, mission.getId(), mission.getName(), userId, null);
    return mission;
  }

  /**
   * Checks the echo against the {@code mission_ownership} row and moves it to the new owner,
   * flushing immediately. A missing row is first created with the old owner at version {@code 0},
   * so the first change reaches version {@code 1}.
   *
   * @param mission the managed mission, still carrying the owner being replaced
   * @param newOwner the owner the row moves to
   * @param expectedVersion the caller's echo
   * @return the row's version after the change
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the echo is stale
   */
  private long upsertMissionOwnership(
      @NotNull Mission mission, @NotNull User newOwner, long expectedVersion) {
    Optional<MissionOwnership> existing =
        missionOwnershipRepository.findByMissionId(mission.getId());
    long currentVersion =
        existing.map(MissionOwnership::getVersion).map(v -> v == null ? 0L : v).orElse(0L);
    if (expectedVersion != currentVersion) {
      throw new ObjectOptimisticLockingFailureException(
          MissionOwnership.class, existing.map(MissionOwnership::getId).orElse(mission.getId()));
    }
    MissionOwnership ownership =
        existing.orElseGet(
            () -> {
              MissionOwnership fresh = new MissionOwnership();
              fresh.setMission(mission);
              fresh.setOwner(mission.getOwner());
              return missionOwnershipRepository.saveAndFlush(fresh);
            });
    ownership.setOwner(newOwner);
    MissionOwnership saved = missionOwnershipRepository.saveAndFlush(ownership);
    return saved.getVersion() == null ? 0L : saved.getVersion();
  }

  /**
   * Re-homes a mission to another org unit, or makes it ownerless when {@code targetOrgUnitId} is
   * {@code null} (REQ-ORG-018). Guarded by {@code owningOrgUnitVersion} only; participants, finance
   * entries, units and the operation keep their own ownership.
   *
   * @param missionId mission to reassign.
   * @param targetOrgUnitId the target org-unit id, or {@code null} for an ownerless mission.
   * @param expectedOwningOrgUnitVersion expected value of {@code Mission.owningOrgUnitVersion}.
   * @return the managed mission with the new owning org unit applied.
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the mission is
   *     unknown.
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedOwningOrgUnitVersion} is stale.
   * @throws org.springframework.security.access.AccessDeniedException when the caller may not
   *     assign to the requested target.
   */
  @Transactional
  public Mission updateOwningOrgUnit(
      @NotNull UUID missionId, UUID targetOrgUnitId, @NotNull Long expectedOwningOrgUnitVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository,
        mission,
        MissionSection.OWNING_ORG_UNIT,
        expectedOwningOrgUnitVersion,
        missionId);

    OrgUnit previous = mission.getOwningOrgUnit();
    OrgUnit target = ownerScopeService.resolveReassignTargetOrgUnit(targetOrgUnitId);
    mission.setOwningOrgUnit(target);
    Mission saved = missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_OWNING_ORG_UNIT_CHANGED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("fromOrgUnit", formatOrgUnitRef(previous))
            .with("toOrgUnit", formatOrgUnitRef(target)));
    return saved;
  }

  /**
   * Formats an org unit as the non-PII audit reference {@code kind:id}.
   *
   * @param orgUnit the org unit to format, or {@code null} for an ownerless target.
   * @return the {@code kind:id} reference, or {@code "none"} when {@code orgUnit} is {@code null}.
   */
  @NotNull
  private static String formatOrgUnitRef(OrgUnit orgUnit) {
    return orgUnit == null ? "none" : orgUnit.getKind() + ":" + orgUnit.getId();
  }

  /**
   * Sets or clears a mission's party lead: a non-null {@code userId} links a user, otherwise a
   * non-blank {@code guestName} is stored, otherwise the lead is cleared. Guarded by {@code
   * partyLeadVersion} only.
   *
   * @param missionId mission to update
   * @param userId registered party-lead reference, or {@code null}
   * @param guestName free-text party-lead handle, or {@code null}
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
    return missionParticipantService.setPartyLead(
        missionId, userId, guestName, expectedPartyLeadVersion);
  }

  /**
   * Adds a co-manager. Co-managers can edit the mission with the same privileges as the owner
   * (except they cannot transfer ownership — see {@link MissionSecurityService#canChangeOwner}).
   */
  @Transactional
  public Mission addManager(@NotNull UUID missionId, @NotNull UUID userId) {
    return missionParticipantService.addManager(missionId, userId);
  }

  /**
   * Removes a co-manager. The owner cannot be removed via this method — use {@link
   * #setMissionOwner} to transfer ownership first.
   */
  @Transactional
  public Mission removeManager(@NotNull UUID missionId, @NotNull UUID userId) {
    return missionParticipantService.removeManager(missionId, userId);
  }
}
