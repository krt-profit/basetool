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
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.MissionParticipantCount;
import de.greluc.krt.profit.basetool.backend.repository.FrequencyTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFrequencyRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionOwnershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the {@code mission} aggregate — the central operational entity of the squadron.
 *
 * <p>A mission groups a roster (participants), a structure (units + crews + ship assignments), a
 * schedule (planned / actual start and end), and a set of frequencies (radio channels). The service
 * covers the entire surface used by the mission detail page: full updates, section-scoped partial
 * updates (core / schedule / flags), the manager + owner management, participant lifecycle (add as
 * user, as guest, remove, check-in / check-out, payout preference), unit + crew structure, and the
 * mission-frequency CRUD.
 *
 * <p>Concurrency-relevant: nearly every write here is paired with an optimistic-lock check against
 * the mission's {@code @Version}. Methods that touch participants / units / crews work on the
 * already-managed aggregate via dirty-checking — see CLAUDE.md's {@code …WithinTransaction} and
 * bulk-update-after-loop patterns. The owner-version is tracked separately in {@code
 * mission_ownership} so an admin's owner-change does not race with a concurrent participant edit
 * and force a 409 on the unrelated path.
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
   * <strong>Do not call from new code.</strong> Kept only because the wider service test suite
   * references the method signature; every controller-facing list endpoint MUST go through {@link
   * #searchMissions(String, Instant, Instant, List, Boolean, UUID, Pageable)} so the
   * MULTI_SQUADRON_PLAN §1 visibility rule is applied. A direct {@code missionRepository.findAll}
   * leaks every squadron's internal missions cross-tenant. Audit finding M-5 (2026-05-20): this
   * method is unused on the controller path and is retained at {@code @Deprecated(forRemoval)} with
   * a hard {@link UnsupportedOperationException} body so a future caller fails immediately instead
   * of silently widening visibility.
   *
   * @param pageable unused
   * @return never; always throws
   * @throws UnsupportedOperationException always — use {@link #searchMissions} instead.
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
   * Returns the lightweight reference projection (id + display name + status + planned start) that
   * drives the mission-picker dropdowns in the warehouse (Lager) views — the per-item association
   * select and the list filter. Includes every {@code PLANNED} / {@code ACTIVE} mission visible to
   * the caller plus the {@code COMPLETED} / {@code CANCELLED} missions whose planned start falls
   * within the last three months, so a just-closed operation stays filterable while ancient ones
   * drop off the list. Squadron-scoped via {@link OwnerScopeService#currentScopePredicate()}: a
   * non-admin caller sees their own OrgUnits' missions PLUS any non-internal mission of any
   * OrgUnit, mirroring {@link #searchMissions(String, Instant, Instant, List, Boolean, UUID,
   * Pageable)}; admins in "all squadrons" mode get the unfiltered cross-staffel list. Audit finding
   * H-4: the previous implementation leaked the names of foreign squadrons' internal missions
   * through this dropdown.
   *
   * @return lightweight reference projection of the picker-visible missions for the caller
   */
  public List<de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto>
      findAllActiveReference() {
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
   * Free-text search over mission name, description, location and operation name. Optional filters
   * narrow by status, time window and operation. Used by the mission list page.
   *
   * @return paged matching missions
   */
  /**
   * Registration counts for the missions of one list page, in a single grouped statement.
   *
   * <p>The list shows "{n} angemeldet" per mission, and a {@code MissionParticipant} row
   * <em>is</em> the registration — there is no accept/decline state to weigh. Reading the figure
   * from each mission's lazy {@code participants} collection would be a SELECT per row, which is
   * exactly the N+1 REQ-DATA-003 forbids, so it is asked for once for the whole page.
   *
   * @param missionIds the missions on the page; an empty collection short-circuits without a query.
   * @return count per mission id; missions with no participants are <em>absent</em> rather than
   *     zero, so callers must read through a default.
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
    // M-1: defence-in-depth. Anonymous callers may never see internal missions, even if a future
    // controller forgets to pass {@code isInternal=false}. Force the filter here so the data
    // layer is the authoritative gate — a regression in the controller cannot widen the leak.
    Boolean effectiveIsInternal = isInternal;
    if (!authHelperService.isAuthenticated()) {
      effectiveIsInternal = Boolean.FALSE;
    }
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
   * Returns the mission.
   *
   * @param id mission primary key
   * @return the mission
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  public Mission getMissionById(@NotNull UUID id) {
    return missionRepository
        .findById(id)
        .orElseThrow(() -> new NotFoundException("Mission not found"));
  }

  /**
   * Statuses a mission may carry to qualify as the home-page "next mission". Only live operational
   * missions ({@code PLANNED} / {@code ACTIVE}) are eligible — a {@code COMPLETED} or {@code
   * CANCELLED} mission with a future planned start must never surface in the banner.
   */
  private static final List<String> NEXT_MISSION_STATUSES = List.of("PLANNED", "ACTIVE");

  /**
   * Returns the next upcoming mission by planned-start time. Drives the home-page "next mission"
   * banner. Only missions in status {@code PLANNED} or {@code ACTIVE} are considered. {@code
   * allowInternal=true} (for authenticated callers) includes internal missions; guests see only
   * public missions.
   *
   * <p>Org-unit scoping (REQ-MISSION-008): when the caller has an effective org-unit scope — a
   * plain member's own unit(s), or the cascade-expanded reach of a Bereich/OL leader (REQ-ORG-015)
   * — the banner is restricted to missions owned by those org units; foreign missions, including
   * other units' public ones, are excluded so the banner answers "what is <em>my</em> unit heading
   * towards". When the caller has <em>no</em> org-unit scope — an admin in "all squadrons" mode, an
   * anonymous guest, or an authenticated user who belongs to no org unit — the banner falls back to
   * the unchanged organisation-wide next mission. The scope vector is the same {@link
   * OwnerScopeService#currentScopePredicate()} the scoped mission lists use.
   *
   * @param allowInternal whether internal missions should be included
   * @return the next mission, or empty when none upcoming
   */
  public Optional<Mission> getNextMission(boolean allowInternal) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    Instant now = Instant.now();
    Optional<Mission> next;
    if (scope.adminAllScope()
        || (scope.activeOrgUnitId() == null && scope.memberOrgUnitIds().isEmpty())) {
      // No org-unit scope: admin all-scope, anonymous guest, or an authenticated user with no
      // membership. Unchanged behaviour — the soonest PLANNED/ACTIVE mission across the whole
      // organisation (internal ones only for members). REQ-MISSION-008.
      next = findNextMissionHead(now, allowInternal);
    } else {
      // The caller has an org-unit scope: restrict the banner to missions owned by those org units.
      // A Bereich/OL leader's scope already carries the cascaded descendants (REQ-ORG-015 via
      // OwnerScopeService.currentMemberOrgUnitIds); a plain member sees only their own units.
      next = findNextScopedMissionHead(now, allowInternal, scope);
    }
    // The limit-1 lookups above are intentionally not graphed — a collection fetch combined with
    // the
    // limit forces Hibernate into in-memory pagination (HHH90003004). Re-fetch the single hit by id
    // through the graphed findById so participants / assignedUnits are eagerly loaded for the
    // mapper
    // (and the home-page guest redaction) without paginating the whole upcoming-mission set.
    return next.map(Mission::getId).flatMap(missionRepository::findById);
  }

  /**
   * Resolves the ungraphed limit-1 head of the unscoped next-mission lookup, filtered to {@link
   * #NEXT_MISSION_STATUSES}. Split out from {@link #getNextMission(boolean)} only so the long
   * derived-query method names sit at a shallow enough indentation to stay within the line-length
   * limit; it carries no behaviour of its own beyond the {@code allowInternal} branch.
   *
   * @param now exclusive lower bound on {@code plannedStartTime}
   * @param allowInternal whether internal missions should be included
   * @return the next-mission head (id-only matters; caller re-fetches through the graphed findById)
   */
  private Optional<Mission> findNextMissionHead(Instant now, boolean allowInternal) {
    if (allowInternal) {
      return missionRepository
          .findFirstByPlannedStartTimeAfterAndStatusInOrderByPlannedStartTimeAsc(
              now, NEXT_MISSION_STATUSES);
    }
    return missionRepository
        .findFirstByPlannedStartTimeAfterAndIsInternalFalseAndStatusInOrderByPlannedStartTimeAsc(
            now, NEXT_MISSION_STATUSES);
  }

  /**
   * Resolves the ungraphed limit-1 head of the org-unit-scoped next-mission lookup
   * (REQ-MISSION-008) for a caller that has an effective scope. Delegates to {@link
   * MissionRepository#findNextScopedMission} with a {@code PageRequest.of(0, 1)} so only the
   * soonest matching mission is fetched, then returns its head. The scope's {@code activeOrgUnitId}
   * (when pinned) or {@code memberOrgUnitIds} (the cascade-expanded membership union) selects the
   * eligible owning org units; {@code allowInternal} mirrors the unscoped variant's
   * internal-visibility gate.
   *
   * @param now exclusive lower bound on {@code plannedStartTime}
   * @param allowInternal whether internal missions should be included
   * @param scope the caller's effective org-unit scope (never admin-all / never empty here)
   * @return the next-mission head (id-only matters; caller re-fetches through the graphed findById)
   */
  private Optional<Mission> findNextScopedMissionHead(
      Instant now, boolean allowInternal, ScopePredicate scope) {
    return missionRepository
        .findNextScopedMission(
            now,
            NEXT_MISSION_STATUSES,
            allowInternal,
            scope.activeOrgUnitId(),
            scope.memberOrgUnitIds(),
            PageRequest.of(0, 1))
        .stream()
        .findFirst();
  }

  /**
   * Persists a new mission from the request DTO. Every server-managed column ({@code id}, {@code
   * version}, the section counters, {@code owner}, {@code owningSquadron}, {@code parent}, the
   * sub-aggregate collections) is set inside this method — the request record carries only
   * caller-controllable fields, which makes the mass-assignment vector exploited in audit finding
   * C-3 structurally impossible.
   *
   * @param request create payload (already validated by Bean Validation at the controller boundary)
   * @return the persisted mission entity
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when {@code
   *     operationId} does not resolve
   */
  @Transactional
  public Mission createMission(@NotNull CreateMissionRequest request) {
    Mission mission = new Mission();
    applyCreatePayload(mission, request);

    // Fail-fast on the time-window validation BEFORE the userService / ownerScopeService
    // round-trips so a malformed payload does not waste a DB / security-context lookup.
    validateMissionTimes(mission);

    userService.getCurrentUser().ifPresent(mission::setOwner);

    if (mission.getOwner() != null) {
      // R5.d.d: owner present → route through the shared picker resolver, which honours an
      // explicit owningOrgUnitId from the form if the caller is a member of that org unit, and
      // falls back to the owner's home Staffel when the field is null. The *nullable* resolver is
      // used so a membershipless leadership user ("Bereichsleitung", who belongs to no Staffel/SK
      // but may plan org-wide missions) resolves to a null owner instead of a 400 — the resulting
      // ownerless mission is attributable through its owner and scoped by the mission visibility
      // rules (public unless internal). See OwnerScopeService.resolveOrgUnitForPickerOutputNullable
      // and V144.
      mission.setOwningOrgUnit(
          ownerScopeService.resolveOrgUnitForPickerOutputNullable(
              mission.getOwner(), request.owningOrgUnitId()));
    } else {
      // No authenticated owner (admin in "all squadrons" mode or anonymous fallback) — the
      // picker field, if supplied, cannot be membership-validated. Honour the historical
      // behaviour: stamp from the active org-unit scope.
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
   * Seeds the optional goals (Ziele) and steps (Ablauf) carried in the create request onto the
   * freshly-persisted mission, each at a contiguous {@code orderIndex} in submitted order. Both
   * lists are optional — a {@code null} or empty list seeds nothing — so the create form can lay
   * out goals and steps in the same action instead of a follow-up per-item call, while a plain
   * create still works unchanged. Delegates to the timeline service's {@code *AtCreate} methods,
   * which audit each goal/step and skip the section-version guard (a just-created mission has no
   * concurrent editor to lose a race against).
   *
   * @param mission the managed, already-persisted mission
   * @param request the validated create payload carrying the optional {@code objectives}/{@code
   *     steps} lists
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
   * Copies the caller-controllable fields of {@link CreateMissionRequest} onto a fresh {@link
   * Mission} entity. Shared by {@link #createMission(CreateMissionRequest)} and {@link
   * #addSubMission(UUID, CreateMissionRequest)}; the latter additionally wires up {@code parent}
   * and inherits the owning squadron. Operation lookup runs here so both create paths fail the same
   * way for an unknown operation id.
   *
   * @param mission target entity (caller owns its identity and version)
   * @param request validated create payload
   */
  private void applyCreatePayload(Mission mission, CreateMissionRequest request) {
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
          operationRepository
              .findById(request.operationId())
              .orElseThrow(() -> new NotFoundException("Operation not found"));
      mission.setOperation(op);
    } else {
      mission.setOperation(null);
    }
  }

  /**
   * Full update of a mission's metadata + structural references. Validates optimistic lock,
   * resolves shallow references (operation, location, frequencies). Participant / unit / crew lists
   * are NOT touched here — they have their own dedicated mutators (see {@link #addParticipant},
   * {@link #addUnitToMission}, etc.) so the per-row optimistic-lock checks on those flows do not
   * collide with a mission-level edit.
   *
   * <p>Bumps all three section counters ({@code coreVersion}, {@code scheduleVersion}, {@code
   * flagsVersion}) on success because a full-replace is semantically an overwrite of every section;
   * concurrent section-scoped patches in flight will get a 409 on their next save. Prefer the
   * dedicated section endpoints {@link #updateCoreSection}, {@link #updateScheduleSection}, {@link
   * #updateFlagsSection} for multi-user-friendly edits.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the mission or
   *     any referenced id is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the supplied
   *     version is stale
   */
  @Transactional
  public Mission updateMission(@NotNull UUID missionId, @NotNull UpdateMissionRequest request) {
    // Since #1114 every mutable mission scalar is @OptimisticLock(excluded = true), so a full
    // overwrite no longer bumps the row @Version by itself. Load under OPTIMISTIC_FORCE_INCREMENT
    // so the flush still increments+checks @Version (WHERE version = loaded) — that keeps this
    // legacy whole-mission path's "two concurrent overwrites 409 against each other" guarantee. The
    // client-echo check below additionally rejects a form that was already stale at load time.
    Mission mission =
        missionRepository
            .findByIdForFullReplace(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    if (!mission.getVersion().equals(request.version())) {
      throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
          Mission.class, missionId);
    }

    // Decide the effective actualStartTime UP FRONT, before mutating any setter on the managed
    // entity — the auto-stamp branch reads the current (pre-mutation) status, so capturing it
    // here keeps the decision honest and avoids a far-away usage of the snapshot variable that
    // Checkstyle's VariableDeclarationUsageDistance would otherwise flag.
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
          operationRepository
              .findById(request.operationId())
              .orElseThrow(() -> new NotFoundException("Operation not found"));
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
   * Updates only the core (master-data) section of a mission. Validates the dedicated {@code
   * coreVersion} counter so concurrent edits on {@code schedule} or {@code flags} do not invalidate
   * this form. Sub-collections (participants, units, finance) are not touched and, thanks to
   * {@code @OptimisticLock(excluded = true)}, do not bump the parent version either.
   *
   * <p>When the status transitions from anything other than {@code ACTIVE} to {@code ACTIVE} and
   * {@link Mission#getActualStartTime()} is still {@code null}, this method ALSO bumps {@code
   * scheduleVersion} and stamps {@code actualStartTime = now()} via {@link
   * #bumpActualStartTimeOnActivationWithinTransaction(Mission)}. This is by design: the activation
   * crosses the core/schedule boundary, and the schedule counter must move so concurrent schedule
   * edits surface the change as a 409 instead of silently overwriting the auto-stamped value.
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    enforceSectionVersion(
        missionRepository, mission, MissionSection.CORE, expectedCoreVersion, missionId);

    // Cross-section auto-stamp FIRST, before mutating mission.status — the condition reads the
    // OLD status, so it must run before the setter below. Setting actualStartTime here is safe
    // because it touches a different field; the later setters do not overwrite it.
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
          operationRepository
              .findById(operationId)
              .orElseThrow(() -> new NotFoundException("Operation not found"));
      mission.setOperation(op);
    } else {
      mission.setOperation(null);
    }

    // coreVersion was already bumped atomically by enforceSectionVersion above; the flush writes
    // the
    // dirtied core scalars (+ the advanced counter) via @DynamicUpdate without touching other
    // sections' columns.
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
   * Updates only the schedule section of a mission. All timestamps are processed and stored in UTC.
   * Validates the dedicated {@code scheduleVersion} counter; concurrent core or flags edits do not
   * invalidate this form.
   *
   * <p>Setting {@code actualEndTime} also closes any open participant end-times (dirty-checked on
   * the managed entities; the participants' own {@code @Version} fields will be bumped naturally by
   * Hibernate at flush time, mission section counters are not affected by that).
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    enforceSectionVersion(
        missionRepository, mission, MissionSection.SCHEDULE, expectedScheduleVersion, missionId);
    mission.setMeetingTime(meetingTime);
    mission.setPlannedStartTime(plannedStartTime);
    mission.setPlannedEndTime(plannedEndTime);
    mission.setActualStartTime(actualStartTime);
    mission.setActualEndTime(actualEndTime);

    // A failed time-order validation rolls the transaction back, undoing the counter bump above.
    validateMissionTimes(mission);
    Mission saved = missionRepository.save(mission);

    if (actualEndTime != null) {
      // Single set-based clamp instead of an O(roster) entity loop (#1146, CLAUDE.md bulk-update
      // rule): the per-row loop gave every checked-in participant a versioned UPDATE at commit, so
      // the whole schedule write 409'd if any of up-to-500 rows was concurrently modified, and
      // every
      // in-flight check-out racing this sweep 409'd at its own commit. One atomic UPDATE (row locks
      // serialize it; flushAutomatically lands the schedule scalars first) cannot 409 against
      // concurrent participant writes and shrinks the transaction from O(roster) to one statement.
      // The slim schedule response carries scalars only, so the now-stale in-memory participant
      // endTimes do not affect it.
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
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
   * Cross-section helper invoked from {@link #updateCoreSection} when a status transition to {@code
   * ACTIVE} requires auto-stamping {@code actualStartTime}. Operates on the already-managed entity
   * via dirty-checking (no {@code save()}/{@code flush()} of its own — see CLAUDE.md's {@code
   * …WithinTransaction} pattern) and bumps the schedule counter so other open schedule editors get
   * a 409 instead of silently overwriting the stamp.
   *
   * @param mission managed mission entity in the current transaction
   */
  private void bumpActualStartTimeOnActivationWithinTransaction(@NotNull Mission mission) {
    mission.setActualStartTime(Instant.now());
    // Deliberate in-memory (unconditional) schedule bump, NOT the DB-enforced
    // enforceSectionVersion:
    // the activation carries no client-echoed scheduleVersion, so there is nothing to check
    // against.
    // @DynamicUpdate flushes only actual_start_time + schedule_version; a concurrent schedule
    // editor
    // that echoed the pre-activation version still 409s (its conditional bump matches 0 rows once
    // this commits first), which is the invalidation this cross-section poke exists to produce.
    bumpSectionVersion(mission, MissionSection.SCHEDULE);
  }

  /**
   * Deletes a mission. The cascade unlinks inventory items and refinery orders (rather than
   * deleting them) so individual member contributions survive the mission delete. Mission
   * participants, finance entries, units, crews and frequencies ARE deleted because they only exist
   * in the context of this mission.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the mission is
   *     unknown
   */
  @Transactional
  public void deleteMission(@NotNull UUID missionId) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    // Snapshot the label/id BEFORE the delete so the audit row survives the removed aggregate.
    final UUID deletedMissionId = mission.getId();
    final String deletedMissionName = mission.getName();

    // Variante C (REQ-INV-027): an inventory entry's mission earmark lives in the
    // mission-allocation
    // table with an ON DELETE CASCADE FK, so deleting the mission drops those slices automatically
    // while the inventory rows survive as (partially) unassigned stock — no manual detach needed.

    // Detach refinery orders
    if (mission.getRefineryOrders() != null) {
      mission.getRefineryOrders().forEach(order -> order.setMission(null));
      mission.getRefineryOrders().clear();
    }

    // Detach sub-missions
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
   * Adds an authenticated user as a participant on a mission. Convenience overload that delegates
   * to the full-form {@link #addParticipant(UUID, ParticipantForm)} with default values for the
   * optional fields.
   */
  @Transactional
  public Mission addParticipant(@NotNull UUID missionId, @NotNull UUID userId) {
    return missionParticipantService.addParticipant(missionId, userId);
  }

  /**
   * Mid-form participant add — accepts a user reference, optional guest name (when the user isn't
   * authenticated), an optional desired job type, and an optional comment. Convenience overload
   * that delegates to the full form with {@code orgUnitIds=null} and no explicit payout choice.
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
   * Full-form participant add. Resolves the user reference from {@code userId} (or by
   * case-insensitive {@code guestName} match against existing users — promotes a guest entry to a
   * linked-user entry when the guest name turns out to be a real member).
   *
   * <p>Org-unit affiliations are stamped per kind of participant:
   *
   * <ul>
   *   <li><b>Registered user</b> — affiliations are auto-derived from the user's memberships (every
   *       Staffel and Spezialkommando they belong to); the submitted {@code orgUnitIds} are
   *       ignored. A user with no membership at all gets no affiliation (no more wrong IRIDIUM
   *       fallback).
   *   <li><b>Guest</b> — the caller-submitted {@code orgUnitIds} are honoured after the
   *       authorization filter in {@code MissionParticipantService.resolveGuestSubmittedOrgUnits}
   *       (anonymous callers cannot label a guest at all; authenticated callers may label only org
   *       units they can edit).
   * </ul>
   *
   * <p>{@code payoutPreference} (nullable) fixes the per-mission payout choice at sign-up time —
   * the sign-up modal's "Auszahlungsart" select. A non-null value wins over the registered user's
   * profile default (REQ-MISSION-002); {@code null} keeps the existing default chain (profile
   * default for users, entity default {@code PAYOUT} for guests).
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
      java.util.List<UUID> orgUnitIds,
      PayoutPreference payoutPreference) {
    return missionParticipantService.addParticipant(
        missionId, userId, guestName, desiredJobTypeId, comment, orgUnitIds, payoutPreference);
  }

  /**
   * Look up a single participant on a mission. Verifies the participant actually belongs to the
   * named mission — a participant id that exists but is attached to a different mission throws 404,
   * matching what {@link MissionSecurityService#canAccessParticipant} expects.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the participant
   *     does not exist on this mission
   */
  public MissionParticipant getParticipant(@NotNull UUID missionId, @NotNull UUID participantId) {
    return missionParticipantService.getParticipant(missionId, participantId);
  }

  /**
   * Returns all participants of a mission that are not yet assigned to any unit (crew). Used to
   * filter the "Crew zuweisen" dropdown so only unassigned participants are selectable.
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
   * Updates a participant's per-mission attributes (job type, ship, unit, crew, guest name, payout
   * preference). Per-participant optimistic lock via the participant's own {@code @Version} field —
   * the wider mission's version is NOT bumped, so concurrent participant edits don't collide with
   * each other or with mission-level edits.
   *
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
      java.util.List<UUID> orgUnitIds,
      PayoutPreference payoutPreference,
      String guestName,
      Long version) {
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
        version);
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
    return missionStructureService.addUnitToMission(
        missionId, name, shipTypeId, shipId, highValueUnit, frequency, responsibleUserId, note);
  }

  /**
   * Updates a unit's name and the assigned ship. Per-unit optimistic lock via the client-echoed
   * {@code expectedVersion} — concurrent unit edits across the mission don't collide, and a stale
   * full-form save is rejected with a 409 (#1131). Thin delegation to {@link
   * MissionStructureService}.
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
    return missionStructureService.getSelectableUnitShips(missionId);
  }

  /**
   * Removes a unit. Participants that were assigned to the unit fall back to the unassigned bucket
   * (their {@code unit}/{@code crew} references are cleared, the rows themselves stay).
   */
  @Transactional
  public Mission removeMissionUnit(@NotNull UUID missionId, @NotNull UUID unitId) {
    return missionStructureService.removeMissionUnit(missionId, unitId);
  }

  // --- Ablauf steps (procedure timeline) ---

  /**
   * Appends a step to the mission's Ablauf timeline. The new step lands at the end ({@code
   * orderIndex = max + 1}) and is initially not done. Validates and bumps the dedicated {@code
   * stepsVersion} section counter so a concurrent step edit surfaces as a 409 while never colliding
   * with a parallel core / schedule / flags edit.
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
   * Reorders the mission's Ablauf steps. {@code orderedStepIds} must be exactly the mission's step
   * ids in the desired order; {@code orderIndex} is reassigned 0..n-1 by dirty-checking the managed
   * children (no per-child save, no bulk {@code clearAutomatically} query — so no detach/merge
   * double-version bump). The {@code stepsVersion} optimistic guard serialises concurrent reorders,
   * making a pessimistic lock unnecessary. Records a single reorder event (count only, no titles).
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
   * Toggles a step's shared {@code done} flag to the requested state and bumps {@code
   * stepsVersion}. The single "current phase" (first not-done step) is derived on read, never
   * stored.
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

  // --- Mission goals (Ziele) ---

  /**
   * Appends a goal (Ziel) to a mission at the end of the list (next {@code orderIndex}) and bumps
   * {@code objectivesVersion}. Guarded by the dedicated goals-section counter so editing the goals
   * never collides with a concurrent core / schedule / flags / Ablauf edit. Records an audit event
   * carrying the goal id and kind only — never the title (user free text).
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
   * Reorders the mission's goals. {@code orderedObjectiveIds} must be exactly the mission's goal
   * ids in the desired order; {@code orderIndex} is reassigned 0..n-1 by dirty-checking the managed
   * children (no per-child save, no bulk {@code clearAutomatically} query — so no detach/merge
   * double-version bump). The {@code objectivesVersion} optimistic guard serialises concurrent
   * reorders, making a pessimistic lock unnecessary. Records a single reorder event (count only).
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

  /**
   * Updates a crew's assigned job types, guarded by the client-echoed {@code expectedVersion} so a
   * stale save 409s instead of silently reverting a concurrent edit (#1131). Thin delegation to
   * {@link MissionStructureService}.
   */
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
   * Creates a sub-mission under a parent mission. Sub-missions are independent missions that roll
   * up to their parent in the operation overview. {@code parent} and {@code owningSquadron} are
   * stamped server-side from the path-resolved parent — the request body has no say in either,
   * which is the mass-assignment fix from audit finding C-3.
   *
   * @param parentMissionId path-resolved parent id (authoritative)
   * @param request create payload (validated at the controller boundary)
   * @return the persisted sub-mission entity
   * @throws NotFoundException when {@code parentMissionId} or {@code operationId} does not resolve
   */
  @Transactional
  public Mission addSubMission(
      @NotNull UUID parentMissionId, @NotNull CreateMissionRequest request) {
    Mission parent =
        missionRepository
            .findById(parentMissionId)
            .orElseThrow(() -> new NotFoundException("Parent mission not found"));

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
   * Creates or updates a <em>typed</em> radio frequency entry on a mission. Single endpoint for
   * both because the {@code (mission, frequencyType)} pair discriminates: the row is inserted on
   * the first set of a channel and updated in place thereafter.
   *
   * <p><b>Race-free, last-writer-wins by design (#1148).</b> The former find-in-memory-then-save
   * was a check-then-act TOCTOU against {@code UNIQUE(mission_id, frequency_type_id)}: two managers
   * setting a never-set channel near-simultaneously both missed the find, both INSERTed, and the
   * loser got an unresolvable 409 (the endpoint exposes no version to refresh) though a plain retry
   * would have succeeded. It now goes through a single atomic {@code INSERT … ON CONFLICT DO
   * UPDATE} ({@link MissionFrequencyRepository#upsertTypedFrequency}), so a concurrent
   * first-time-set never conflicts and the typed upsert's documented last-writer-wins semantics
   * hold for an existing row too — matching the frequencies collection being
   * {@code @OptimisticLock(excluded = true)} (a frequency change never 409s a concurrent
   * core/schedule/flags edit). The custom-frequency twin, which carries a real client version,
   * keeps its explicit optimistic-lock check.
   *
   * @param missionId the mission to set the channel on.
   * @param frequencyTypeId the typed channel.
   * @param value the frequency value (range-validated at the boundary).
   * @return the managed mission, re-fetched so its {@code frequencies} collection reflects the
   *     upserted row (the native upsert cleared the persistence context).
   * @throws NotFoundException when the mission or the frequency type is unknown.
   */
  @Transactional
  public Mission addOrUpdateMissionFrequency(
      @NotNull UUID missionId, @NotNull UUID frequencyTypeId, @NotNull BigDecimal value) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
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
    // The native upsert bypassed the persistence context (clearAutomatically), so re-load a fresh
    // managed mission whose frequencies collection includes the upserted row for the response.
    return missionRepository
        .findById(missionId)
        .orElseThrow(() -> new NotFoundException("Mission not found"));
  }

  /**
   * Adds a custom (mission-specific) radio frequency — a free-text label plus a value — to a
   * mission (REQ-MISSION-014). The new row leaves {@link MissionFrequency#getFrequencyType()} null;
   * the check constraint added in V201 enforces the typed-XOR-named invariant at the data layer.
   *
   * <p>The mission's {@code frequencies} collection is {@code @OptimisticLock(excluded = true)}, so
   * persisting the child neither bumps the mission's global {@code @Version} nor any section
   * counter — a frequency change never 409s a concurrent core/schedule/flags edit, matching the
   * typed upsert.
   *
   * @param missionId the mission to attach the channel to.
   * @param name the free-text channel label (validated non-blank ≤100 chars at the boundary).
   * @param value the frequency value (range-validated 0 – 999.99 at the boundary).
   * @return the managed mission with the new custom frequency attached.
   * @throws NotFoundException when the mission is unknown.
   */
  @Transactional
  public Mission addCustomMissionFrequency(
      @NotNull UUID missionId, @NotNull String name, @NotNull BigDecimal value) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    MissionFrequency freq = new MissionFrequency();
    freq.setMission(mission);
    freq.setName(name.trim());
    freq.setValue(value);
    mission.getFrequencies().add(freq);
    MissionFrequency saved = missionFrequencyRepository.save(freq);

    // Audit finding parity with the typed path: the label is user free text, so log only the row id
    // (never the name), per REQ-AUDIT-001's "no free text / no PII in the details payload" rule.
    auditService.record(
        AuditEventType.MISSION_FREQUENCY_CHANGED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("custom", saved.getId()));
    return mission;
  }

  /**
   * Updates the label and value of an existing custom frequency (REQ-MISSION-014). Optimistic-locks
   * on the frequency row's own {@code @Version} (echoed back from the rendered row); a stale
   * version surfaces as HTTP 409. Rejects an attempt to reach a typed (global) frequency row
   * through this path so the two families cannot be crossed.
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

    MissionFrequency freq =
        mission.getFrequencies().stream()
            .filter(f -> f.getId() != null && f.getId().equals(frequencyId))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Frequency not found in this mission"));

    if (freq.getFrequencyType() != null) {
      throw new IllegalArgumentException(
          "Cannot edit a typed frequency through the custom-frequency endpoint");
    }

    long current = freq.getVersion() == null ? 0L : freq.getVersion();
    if (!expectedVersion.equals(current)) {
      throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
          MissionFrequency.class, frequencyId);
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));

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
   * Transfers mission ownership to another user. The previous owner is automatically added to the
   * manager list so they don't lose all access in one click.
   *
   * <p>Versioning: bumps the dedicated {@code mission_ownership} version, NOT the mission's main
   * version — so concurrent participant or finance edits don't race with the owner change.
   */
  @Transactional
  public Mission setMissionOwner(@NotNull UUID missionId, @NotNull UUID userId) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    mission.setOwner(user);
    // Mission.owner is @OptimisticLock(excluded=true), so this does NOT bump Mission.version.
    // Sub-level optimistic locking for ownership is provided by the dedicated MissionOwnership
    // aggregate; see updateMissionOwner(UUID,UUID,Long) below.
    upsertMissionOwnership(mission, user, null);
    auditService.record(
        AuditEventType.MISSION_OWNER_CHANGED, mission.getId(), mission.getName(), userId, null);
    return mission;
  }

  /**
   * Version-checked owner change for multi-user concurrency (Option A). The {@code
   * expectedOwnershipVersion} must match {@link MissionOwnership#getVersion()}; otherwise a 409
   * {@link org.springframework.orm.ObjectOptimisticLockingFailureException} is raised.
   *
   * <p>This method intentionally does NOT bump {@code Mission.version} (the owner association is
   * excluded from parent optimistic locking), so concurrent edits on other sections of the same
   * mission remain unaffected.
   */
  @Transactional
  public Mission updateMissionOwner(
      @NotNull UUID missionId, @NotNull UUID userId, @NotNull Long expectedOwnershipVersion) {
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    User user =
        userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    mission.setOwner(user);
    upsertMissionOwnership(mission, user, expectedOwnershipVersion);
    auditService.record(
        AuditEventType.MISSION_OWNER_CHANGED, mission.getId(), mission.getName(), userId, null);
    return mission;
  }

  /**
   * Returns the current optimistic-lock version of the mission ownership aggregate (0 if absent).
   *
   * @param missionId mission id
   * @return current value of the dedicated {@code mission_ownership.version} field — frontend
   *     echoes this back on the next owner-change call so two admins editing the same mission
   *     surface a 409 instead of silently overwriting
   */
  public long getMissionOwnershipVersion(@NotNull UUID missionId) {
    return missionOwnershipRepository
        .findByMissionId(missionId)
        .map(mo -> mo.getVersion() == null ? 0L : mo.getVersion())
        .orElse(0L);
  }

  private void upsertMissionOwnership(Mission mission, User newOwner, Long expectedVersion) {
    MissionOwnership ownership =
        missionOwnershipRepository
            .findByMissionId(mission.getId())
            .orElseGet(
                () -> {
                  MissionOwnership fresh = new MissionOwnership();
                  fresh.setMission(mission);
                  return fresh;
                });
    if (expectedVersion != null && ownership.getId() != null) {
      Long currentVersion = ownership.getVersion() == null ? 0L : ownership.getVersion();
      if (!expectedVersion.equals(currentVersion)) {
        throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
            MissionOwnership.class, ownership.getId());
      }
    }
    ownership.setOwner(newOwner);
    missionOwnershipRepository.save(ownership);
  }

  /**
   * Reassigns the owning org unit of an existing mission (REQ-ORG-018 / ADR-0050). The mission is
   * re-homed to {@code targetOrgUnitId} (a Staffel, Spezialkommando, Bereich or
   * Organisationsleitung) or made an ownerless leadership mission when {@code targetOrgUnitId} is
   * {@code null}. The target is validated against the caller's assignable scope by {@link
   * OwnerScopeService#resolveReassignTargetOrgUnit(UUID)} — the orthogonal second gate on top of
   * the per-mission {@code canChangeOwner} gate the controller enforces.
   *
   * <p>Versioning: validates and bumps the dedicated {@code owningOrgUnitVersion} counter only. The
   * association and column are {@code @OptimisticLock(excluded = true)}, so the global {@code
   * Mission.version} and the other section counters stay untouched and concurrent edits on other
   * sections of the same mission remain valid (Option A / multi-user concurrency).
   *
   * <p>Re-homing retroactively re-scopes who may see/edit the mission (the {@code owningOrgUnit} +
   * {@code isInternal} visibility gates); it deliberately does NOT cascade to the mission's
   * participants, finance entries, units or linked operation, which keep their own ownership.
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
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
    // Audit detail carries org-unit identifiers + kinds only — no PII, no user free text (the
    // mission name is the entity label, handled separately by the audit record).
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
   * Formats an org unit as a non-PII audit reference ({@code <kind>:<id>}), or {@code "none"} for
   * an ownerless target. Used in the {@link AuditEventType#MISSION_OWNING_ORG_UNIT_CHANGED} detail
   * payload, which must carry identifiers and kinds only — never names or other free text.
   *
   * @param orgUnit the org unit to format, or {@code null} for an ownerless target.
   * @return a stable {@code kind:id} reference, or {@code "none"} when {@code orgUnit} is {@code
   *     null}.
   */
  private static String formatOrgUnitRef(OrgUnit orgUnit) {
    return orgUnit == null ? "none" : orgUnit.getKind() + ":" + orgUnit.getId();
  }

  /**
   * Assigns or clears a mission's party lead (Partyleiter). The party lead is either a linked
   * registered user or a free-text guest handle — mutually exclusive, mirroring the participant
   * {@code user}/{@code guestName} model. Free-text-to-user resolution is performed by the caller
   * (controller, like the participant-add endpoints); this method persists whatever {@code userId}
   * / {@code guestName} it is handed:
   *
   * <ul>
   *   <li>{@code userId != null} → link the registered user, clear any guest handle;
   *   <li>{@code userId == null} and {@code guestName} non-blank → store the trimmed guest handle,
   *       clear any linked user;
   *   <li>both {@code null}/blank → clear the party lead entirely.
   * </ul>
   *
   * <p>Versioning: validates and bumps the dedicated {@code partyLeadVersion} counter only. The
   * association and columns are {@code @OptimisticLock(excluded = true)}, so the global {@code
   * Mission.version} and the other section counters stay untouched and concurrent edits on other
   * sections of the same mission remain valid (Option A / multi-user concurrency).
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
