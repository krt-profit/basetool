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

import static de.greluc.krt.profit.basetool.backend.mission.internal.MissionSectionVersions.bumpSectionVersion;
import static de.greluc.krt.profit.basetool.backend.mission.internal.MissionSectionVersions.enforceSectionVersion;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.FrequencyTypeRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.LikePatterns;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionCommands;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionDeletionObserver;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFrequency;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantCount;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.OperationSummaryProvider;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionSectionVersions.MissionSection;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
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
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the {@code mission} aggregate's core, schedule, flags, owning org unit, frequencies,
 * owner, sub-missions and search; the timeline, participant and structure sections live in {@link
 * MissionTimelineService}, {@link MissionParticipantService} and {@link MissionStructureService}
 * (ADR-0286).
 *
 * <p>Writes are guarded by the mission's {@code @Version} or a dedicated section counter, and
 * ownership is versioned separately in {@code mission_ownership}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionService implements MissionCommands {

  /** Hard cap on participants per mission, enforced by {@link MissionParticipantService}. */
  public static final int MAX_PARTICIPANTS_PER_MISSION = 500;

  private final MissionRepository missionRepository;
  private final MissionParticipantRepository missionParticipantRepository;
  private final UserRepository userRepository;
  private final FrequencyTypeRepository frequencyTypeRepository;
  private final MissionFrequencyRepository missionFrequencyRepository;
  private final MissionOwnershipRepository missionOwnershipRepository;
  private final OperationSummaryProvider operationSummaryProvider;
  private final MissionDeletionObserver missionDeletionObserver;
  private final UserService userService;
  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelperService;
  private final AuditRecorder auditRecorder;
  private final MissionTimelineService missionTimelineService;

  /** Publishes the mission notifications (REQ-MISSION-021…029). */
  private final MissionNotificationPublisher notificationPublisher;

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
   * Resolves which of the given missions the calling member is signed up for, in one query.
   *
   * @param missionIds the missions on the page; an empty collection runs no query.
   * @return the ids among {@code missionIds} the caller holds a participant row on; empty for an
   *     empty page or a caller without a member id.
   */
  @NotNull
  @Transactional(readOnly = true)
  public Set<UUID> signedUpMissionIds(@NotNull Collection<UUID> missionIds) {
    if (missionIds.isEmpty()) {
      return Set.of();
    }
    return authHelperService
        .currentUserId()
        .map(userId ->
            Set.copyOf(missionParticipantRepository.findMissionIdsSignedUpBy(userId, missionIds)))
        .orElse(Set.of());
  }

  /**
   * Free-text search over mission name, description, location and operation name. Optional filters
   * narrow by status, time window, operation and owning org unit; the caller's visibility scope
   * applies on top. Used by the mission list page.
   *
   * @return paged matching missions
   */
  public Page<Mission> searchMissions(
      @Nullable String query,
      @Nullable Instant start,
      @Nullable Instant end,
      @Nullable List<String> status,
      @Nullable Boolean isInternal,
      @Nullable UUID operationId,
      @Nullable UUID orgUnitId,
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
        orgUnitId,
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
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no match
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
   * banner. With an org-unit scope only that scope's missions count (REQ-MISSION-008); an admin in
   * all-squadrons mode sees the organisation-wide next mission, a caller without any unit only the
   * next public one.
   *
   * @return the next mission, or empty when none upcoming
   */
  public Optional<Mission> getNextMission() {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    Instant now = Instant.now();
    Optional<Mission> next;
    if (scope.adminAllScope()) {
      next = findNextMissionHead(now);
    } else if (hasNoUnitScope(scope)) {
      next = missionRepository.findNextPublicMission(now, NEXT_MISSION_STATUSES);
    } else {
      next = findNextScopedMissionHead(now, scope);
    }
    return next.map(Mission::getId).flatMap(missionRepository::findById);
  }

  /**
   * Returns the upcoming {@code PLANNED} or {@code ACTIVE} missions of the caller's org units
   * (REQ-MISSION-008 scope and pin) merged with those the caller is signed up for, each mission
   * once, soonest planned start first (REQ-MISSION-031).
   *
   * @param limit the most missions to return
   * @return the missions with their owning org unit loaded, empty when none is upcoming
   */
  public List<Mission> getUpcomingOwnAndSignedUp(int limit) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return missionRepository.findUpcomingOwnOrSignedUp(
        Instant.now(),
        NEXT_MISSION_STATUSES,
        scope.adminAllScope(),
        !scope.adminAllScope() && hasNoUnitScope(scope),
        scope.adminAllScope(),
        scope.activeOrgUnitId(),
        scope.memberOrgUnitIds(),
        authHelperService.currentUserId().orElse(null),
        authHelperService.isMemberOrAbove(),
        PageRequest.of(0, limit));
  }

  /**
   * Tells whether the caller has neither a pinned nor a member org unit, so the next-mission
   * lookups can show public missions only (REQ-MISSION-008).
   *
   * @param scope the caller's scope predicate
   * @return {@code true} for a caller without any unit
   */
  private static boolean hasNoUnitScope(@NotNull ScopePredicate scope) {
    return scope.activeOrgUnitId() == null && scope.memberOrgUnitIds().isEmpty();
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
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when {@code operationId}
   *     does not resolve
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
    auditRecorder.record(
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
      mission.setOperationId(requireOperation(request.operationId()));
    } else {
      mission.setOperationId(null);
    }
  }

  /**
   * Confirms that an operation exists before a mission is linked to it.
   *
   * @param operationId the operation to link
   * @return {@code operationId}
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when no operation
   *     matches
   */
  private @NotNull UUID requireOperation(@NotNull UUID operationId) {
    if (!operationSummaryProvider.operations(Set.of(operationId)).containsKey(operationId)) {
      throw new NotFoundException("Operation not found");
    }
    return operationId;
  }

  /**
   * Updates the core section of a mission, guarded by {@code coreVersion}.
   *
   * <p>A transition to {@code ACTIVE} without an actual start time also stamps {@code
   * actualStartTime} and bumps {@code scheduleVersion}.
   *
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when any referenced id
   *     is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     {@code expectedCoreVersion} is stale
   */
  @Transactional
  public Mission updateCoreSection(
      @NotNull UUID missionId,
      @NotNull String name,
      @Nullable String description,
      @Nullable String calendarLink,
      @Nullable String status,
      @Nullable UUID operationId,
      @Nullable String meetingPoint,
      @NotNull Long expectedCoreVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository, mission, MissionSection.CORE, expectedCoreVersion, missionId);

    final String oldStatus = mission.getStatus();
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
    notificationPublisher.statusChanged(mission, oldStatus);

    if (operationId != null) {
      mission.setOperationId(requireOperation(operationId));
    } else {
      mission.setOperationId(null);
    }

    Mission saved = missionRepository.save(mission);
    auditRecorder.record(
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
      @Nullable Instant meetingTime,
      @Nullable Instant plannedStartTime,
      @Nullable Instant plannedEndTime,
      @Nullable Instant actualStartTime,
      @Nullable Instant actualEndTime,
      @NotNull Long expectedScheduleVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository, mission, MissionSection.SCHEDULE, expectedScheduleVersion, missionId);
    final Instant oldMeetingTime = mission.getMeetingTime();
    final Instant oldPlannedStart = mission.getPlannedStartTime();
    final Instant oldPlannedEnd = mission.getPlannedEndTime();
    final Instant oldActualEnd = mission.getActualEndTime();
    mission.setMeetingTime(meetingTime);
    mission.setPlannedStartTime(plannedStartTime);
    mission.setPlannedEndTime(plannedEndTime);
    mission.setActualStartTime(actualStartTime);
    mission.setActualEndTime(actualEndTime);

    validateMissionTimes(mission);
    notificationPublisher.scheduleChanged(mission, oldMeetingTime, oldPlannedStart);
    notificationPublisher.endChanged(mission, oldPlannedEnd, oldActualEnd);
    Mission saved = missionRepository.save(mission);

    if (actualEndTime != null) {
      missionParticipantRepository.clampCheckedInEndTimes(missionId, actualEndTime);
    }
    auditRecorder.record(
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
    auditRecorder.record(
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
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the mission is
   *     unknown
   */
  @Transactional
  public void deleteMission(@NotNull UUID missionId) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");

    final UUID deletedMissionId = mission.getId();
    final String deletedMissionName = mission.getName();
    final Set<UUID> deletedParticipants = MissionNotificationPublisher.participantUserIds(mission);

    missionDeletionObserver.beforeMissionDelete(missionId);

    if (mission.getSubMissions() != null) {
      mission.getSubMissions().forEach(sub -> sub.setParent(null));
      mission.getSubMissions().clear();
    }

    missionRepository.delete(mission);
    notificationPublisher.deleted(deletedMissionId, deletedMissionName, deletedParticipants);
    auditRecorder.record(
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
    auditRecorder.record(
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

    auditRecorder.record(
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

    auditRecorder.record(
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

    auditRecorder.record(
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

    auditRecorder.record(
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
    notificationPublisher.responsibilityAssigned(mission, userId, "OWNER");
    auditRecorder.record(
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
        existing.orElseGet(() -> {
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
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the mission is
   *     unknown.
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedOwningOrgUnitVersion} is stale.
   * @throws org.springframework.security.access.AccessDeniedException when the caller may not
   *     assign to the requested target.
   */
  @Transactional
  public Mission updateOwningOrgUnit(
      @NotNull UUID missionId,
      @Nullable UUID targetOrgUnitId,
      @NotNull Long expectedOwningOrgUnitVersion) {
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
    auditRecorder.record(
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
  private static String formatOrgUnitRef(@Nullable OrgUnit orgUnit) {
    return orgUnit == null ? "none" : orgUnit.getKind() + ":" + orgUnit.getId();
  }

  /**
   * Clears the operation reference of every mission linked to the operation, inside the caller's
   * transaction. The operation column is excluded from the row version and belongs to no section,
   * so no counter moves.
   *
   * @param operationId the operation whose missions are detached
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void detachFromOperation(@NotNull UUID operationId) {
    for (Mission mission : missionRepository.findAllLinkedToOperation(operationId)) {
      mission.setOperationId(null);
    }
  }
}
