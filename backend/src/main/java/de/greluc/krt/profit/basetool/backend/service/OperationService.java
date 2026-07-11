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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationUpdateDto;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD and scoped reads for the {@code operation} aggregate (one or more missions grouped under a
 * single umbrella). The payout engine, the per-participant paid-out toggle and the in-game
 * transfer-fee logic live in {@link OperationPayoutService} (audit Thema 7, #14).
 *
 * <p>Deletion intentionally does NOT cascade to missions — the missions stay alive as
 * operation-less rows so their participant / inventory / refinery history survives. The operation
 * table is small; no caching here, every method goes through the repository directly.
 *
 * <p>The status transition uses the state machine declared on {@code OperationStatus}; admins can
 * override the gate via {@code canOverrideStatus=true} (resolved at the controller boundary from
 * the {@code Authentication}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OperationService {

  /**
   * How many months back a {@code COMPLETED} / {@code CANCELED} operation stays in the
   * operation-picker reference lookup ({@link #findAllReference}). Terminal operations older than
   * this drop out of the picker so it cannot grow unbounded with the total operation count (#1124);
   * mirrors the 3-month terminal cutoff of the mission reference picker.
   */
  private static final int REFERENCE_TERMINAL_CUTOFF_MONTHS = 3;

  private final OperationRepository operationRepository;
  private final MissionRepository missionRepository;
  private final UserService userService;
  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelperService;
  private final AuditService auditService;

  /**
   * Returns paged operation list.
   *
   * @param pageable page request
   * @return paged operation list
   */
  public Page<Operation> getAllOperations(@NotNull Pageable pageable) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return operationRepository.findAllScoped(
        scope.adminAllScope(),
        scope.activeOrgUnitId(),
        scope.memberOrgUnitIds(),
        authHelperService.isMemberOrAbove(),
        authHelperService.currentUserId().orElse(null),
        pageable);
  }

  /**
   * Free-text + status + time-range + scope search across operations. Mirrors {@code
   * MissionService.searchMissions} within the limits of the operation aggregate. Falls back to the
   * full {@link OperationStatus} enum set when {@code status} is {@code null} or empty - the SQL
   * contract requires a non-empty list. The squadron scope is resolved through {@link
   * OwnerScopeService} (admin "all squadrons" mode resolves to {@code null}).
   *
   * <p>Because an operation has no {@code plannedStartTime} of its own, the {@code start}/{@code
   * end} bounds filter on the operation's derived span — {@code start} against the planned start of
   * the earliest linked mission, {@code end} against the planned end of the latest linked mission
   * (see {@link OperationRepository#searchOperations}). Both are optional and forwarded verbatim;
   * the repository's {@code CAST(... AS timestamp) IS NULL} guard disables a {@code null} bound.
   *
   * @param query free-text name/description fragment, may be {@code null}
   * @param start inclusive lower bound on the earliest linked mission's planned start, or {@code
   *     null} to disable
   * @param end inclusive upper bound on the latest linked mission's planned end, or {@code null} to
   *     disable
   * @param status status list (string names of {@link OperationStatus}); {@code null}/empty means
   *     all statuses
   * @param pageable page request
   * @return paged matching operations
   */
  @NotNull
  public Page<Operation> searchOperations(
      @Nullable String query,
      @Nullable Instant start,
      @Nullable Instant end,
      @Nullable List<String> status,
      @NotNull Pageable pageable) {
    List<String> effectiveStatus =
        (status == null || status.isEmpty())
            ? Arrays.stream(OperationStatus.values()).map(Enum::name).toList()
            : status;
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return operationRepository.searchOperations(
        query,
        start,
        end,
        effectiveStatus,
        scope.adminAllScope(),
        scope.activeOrgUnitId(),
        scope.memberOrgUnitIds(),
        authHelperService.isMemberOrAbove(),
        authHelperService.currentUserId().orElse(null),
        pageable);
  }

  /**
   * Returns the slim id + name projection of the operations in the caller's squadron scope that are
   * still picker-relevant, sorted by name. Used by the mission-detail page's operation-picker
   * dropdown so the page render does not need to pull the full {@code OperationDto} payload for
   * every option. Bounded by status + recency (#1124): {@code PLANNED} / {@code ACTIVE} always,
   * {@code COMPLETED} / {@code CANCELED} only within the last {@link
   * #REFERENCE_TERMINAL_CUTOFF_MONTHS} months (by {@code createdAt}), so the picker cannot grow
   * unbounded with the total operation count — mirrors {@code
   * MissionService.findAllActiveReference}.
   *
   * @return slim {@link de.greluc.krt.profit.basetool.backend.model.dto.OperationReferenceDto}
   *     list, filtered by the caller's squadron scope and the status/recency bound
   */
  @NotNull
  public java.util.List<de.greluc.krt.profit.basetool.backend.model.dto.OperationReferenceDto>
      findAllReference() {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    Instant terminalCutoff =
        OffsetDateTime.now(ZoneOffset.UTC)
            .minusMonths(REFERENCE_TERMINAL_CUTOFF_MONTHS)
            .toInstant();
    return operationRepository.findAllReferenceScoped(
        scope.adminAllScope(),
        scope.activeOrgUnitId(),
        scope.memberOrgUnitIds(),
        authHelperService.isMemberOrAbove(),
        authHelperService.currentUserId().orElse(null),
        terminalCutoff);
  }

  /**
   * Returns the operation.
   *
   * @param id operation primary key
   * @return the operation
   * @throws NotFoundException when no match
   */
  public Operation getOperationById(@NotNull UUID id) {
    return Entities.require(operationRepository.findById(id), "Operation not found");
  }

  /**
   * Returns {@code true} if at least one mission of the operation still lacks an {@code
   * actualStartTime} or {@code actualEndTime}. The operation-detail page reads this flag to decide
   * whether to render the "payout figures are preliminary" warning above the payout table — the
   * payout breakdown silently skips missions without both timestamps (see {@link
   * #computeParticipationBreakdown(Operation)}), so percentages can rebalance once every mission is
   * properly closed.
   *
   * <p>Implemented as a single existence-style {@code COUNT > 0} query on {@code Mission} so the
   * detail endpoint only pays one cheap round-trip on top of the existing {@code findById} hit; no
   * lazy collection traversal on the operation graph.
   *
   * @param id operation primary key
   * @return {@code true} when at least one mission has a {@code null actualStartTime} or {@code
   *     actualEndTime}, {@code false} when every mission is fully time-stamped (including the
   *     empty-operation case)
   */
  public boolean hasUnfinishedMissions(@NotNull UUID id) {
    return missionRepository.existsByOperationIdWithUnfinishedActualTime(id);
  }

  /**
   * Persists a new operation. R5.d.e routes the owning-Staffel stamp through the shared picker
   * resolver when an authenticated caller is on the security context.
   *
   * <ul>
   *   <li>Caller resolved AND {@code owningOrgUnitId} provided → resolver validates the picked org
   *       unit against the caller's memberships and stamps it (rejecting a foreign pick with {@code
   *       BadRequestException}).
   *   <li>Caller resolved AND {@code owningOrgUnitId} is {@code null} → resolver falls back to the
   *       caller's single membership. Functionally identical to the legacy "stamp from active
   *       scope" path for the common single-membership case.
   *   <li>Caller resolved with <strong>no</strong> OrgUnit membership AND no picker output →
   *       <em>ownerless leadership operation</em> (#500): the nullable resolver returns {@code
   *       null} instead of 400ing, so organisation leadership ("Bereichsleitung", a member of no
   *       Staffel/SK) can plan org-wide operations. The resulting ownerless operation is visible to
   *       organisation members-or-above (operations have no public escape; see REQ-ORG-009 and
   *       {@link OwnerScopeService#resolveOrgUnitForPickerOutputNullable}).
   *   <li>No authenticated caller (admin in "all squadrons" mode, anonymous fallback) → preserve
   *       the historical {@code OwnerScopeService.currentOrgUnit()} path. The picker UUID, if
   *       supplied, cannot be membership-validated without a user, so it is ignored.
   * </ul>
   *
   * @param operation transient entity
   * @param owningOrgUnitId optional picker output from {@link
   *     de.greluc.krt.profit.basetool.backend.model.dto.OperationCreateDto#owningOrgUnitId}; {@code
   *     null} for the legacy implicit-scope path.
   * @return the persisted operation
   */
  @Transactional
  public Operation createOperation(@NotNull Operation operation, @Nullable UUID owningOrgUnitId) {
    if (operation.getOwningOrgUnit() == null) {
      User caller = userService.getCurrentUser().orElse(null);
      if (caller != null) {
        operation.setOwningOrgUnit(
            ownerScopeService.resolveOrgUnitForPickerOutputNullable(caller, owningOrgUnitId));
      } else {
        ownerScopeService.currentOrgUnit().ifPresent(operation::setOwningOrgUnit);
      }
    }
    Operation saved = operationRepository.save(operation);
    auditService.record(
        AuditEventType.OPERATION_CREATED,
        operation.getId(),
        operation.getName(),
        null,
        AuditDetails.of("status", operation.getStatus()));
    return saved;
  }

  /**
   * Updates an existing operation. Validates the optimistic-lock version and the status state
   * machine (unless the caller has the admin override).
   *
   * @param id operation primary key
   * @param updateDto update payload (carries the expected version + new status)
   * @param canOverrideStatus when true, the state-machine check is bypassed (admin/officer)
   * @return the persisted operation
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   * @throws BadRequestException when the status transition is invalid and override is not granted
   */
  @Transactional
  public Operation updateOperation(
      @NotNull UUID id, @NotNull OperationUpdateDto updateDto, boolean canOverrideStatus) {
    Operation operation = Entities.require(operationRepository.findById(id), "Operation not found");

    OptimisticLock.checkOptionalClient(
        operation.getVersion(), updateDto.version(), Operation.class, id);

    if (!canOverrideStatus && !operation.getStatus().canTransitionTo(updateDto.status())) {
      throw new BadRequestException(
          "Invalid operation status transition: "
              + operation.getStatus()
              + " -> "
              + updateDto.status());
    }

    operation.setName(updateDto.name());
    operation.setDescription(updateDto.description());
    operation.setStatus(updateDto.status());

    // saveAndFlush (not save): OperationController is class-level @Transactional and maps the
    // returned entity to OperationDto INSIDE that still-open transaction. A plain save() defers the
    // UPDATE — and the Hibernate @Version increment — to commit, so the DTO would carry the
    // pre-increment version. The in-place AJAX twin (updateOperationAjax, #576) hands that version
    // straight back to the form; a stale value makes the user's next consecutive save 409. Forcing
    // the flush here bumps @Version before the mapping reads it. Same precedent as JobOrderService.
    Operation saved = operationRepository.saveAndFlush(operation);
    auditService.record(
        AuditEventType.OPERATION_UPDATED,
        operation.getId(),
        operation.getName(),
        null,
        AuditDetails.of("status", operation.getStatus()));
    return saved;
  }

  /**
   * Deletes an operation without deleting its missions.
   *
   * <p>Each linked mission has its {@code operation} reference cleared (Hibernate dirty-checking
   * emits a single {@code UPDATE} per mission). The in-memory collection is cleared explicitly so
   * the bidirectional state stays consistent inside the transaction; the operation row is then
   * removed. Participants, finance entries, inventory items and refinery orders of the underlying
   * missions are untouched — this delete is purely a "ungroup" action.
   *
   * @param id operation primary key
   * @throws NotFoundException when no match
   */
  @Transactional
  public void deleteOperation(@NotNull UUID id) {
    log.info("Deleting operation with ID: {}", id);
    Operation operation = Entities.require(operationRepository.findById(id), "Operation not found");

    // Unlink missions instead of cascading the delete. The mission itself,
    // its participants, finance entries, inventory items and refinery orders
    // all stay intact — only the operation_id back-reference is cleared so
    // the rows can survive as operation-less missions.
    for (Mission mission : operation.getMissions()) {
      mission.setOperation(null);
    }
    operation.getMissions().clear();

    String deletedOperationName = operation.getName();
    operationRepository.delete(operation);
    auditService.record(AuditEventType.OPERATION_DELETED, id, deletedOperationName, null, null);
    log.info("Successfully deleted operation with ID: {}", id);
  }
}
