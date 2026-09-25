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
import de.greluc.krt.profit.basetool.backend.model.dto.OperationReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationUpdateDto;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.LikePatterns;
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
 * CRUD and scoped reads for the {@code operation} aggregate, which groups one or more missions.
 *
 * <p>Deleting an operation keeps its missions. Status changes follow the {@code OperationStatus}
 * state machine unless the caller may override it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OperationService {

  /**
   * Months a {@code COMPLETED} or {@code CANCELED} operation stays in the operation picker ({@link
   * #findAllReference}).
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
   * Searches operations by free text, status, time range and the caller's squadron scope. The time
   * bounds apply to the operation's span: {@code start} to the earliest linked mission's planned
   * start, {@code end} to the latest linked mission's planned end.
   *
   * @param query free-text name/description fragment, may be {@code null}
   * @param start inclusive lower bound on the earliest linked mission's planned start, or {@code
   *     null} to disable
   * @param end inclusive upper bound on the latest linked mission's planned end, or {@code null} to
   *     disable
   * @param status status names of {@link OperationStatus}; {@code null} or empty means all
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
        LikePatterns.escapeNullable(query),
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
   * Returns the id + name projection of the picker-relevant operations in the caller's squadron
   * scope, sorted by name: {@code PLANNED} and {@code ACTIVE} always, {@code COMPLETED} and {@code
   * CANCELED} only when created within the last {@link #REFERENCE_TERMINAL_CUTOFF_MONTHS} months.
   *
   * @return slim {@link de.greluc.krt.profit.basetool.backend.model.dto.OperationReferenceDto} list
   *     within the caller's scope and the status/recency bound
   */
  @NotNull
  public List<OperationReferenceDto> findAllReference() {
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
   * Returns whether at least one mission of the operation lacks an {@code actualStartTime} or
   * {@code actualEndTime}, meaning the payout figures are still preliminary.
   *
   * @param id operation primary key
   * @return {@code true} when a mission lacks an actual start or end time, {@code false} otherwise
   *     (including an operation without missions)
   */
  public boolean hasUnfinishedMissions(@NotNull UUID id) {
    return missionRepository.existsByOperationIdWithUnfinishedActualTime(id);
  }

  /**
   * Persists a new operation and stamps its owning org unit.
   *
   * <p>With an authenticated caller, a picked org unit is validated against the caller's
   * memberships; without a pick the caller's single membership is used, and a caller without any
   * membership creates an ownerless operation (REQ-ORG-009). Without an authenticated caller the
   * pick is ignored and {@code OwnerScopeService.currentOrgUnit()} is stamped.
   *
   * @param operation transient entity
   * @param owningOrgUnitId optional picker output from {@link
   *     de.greluc.krt.profit.basetool.backend.model.dto.OperationCreateDto#owningOrgUnitId}, or
   *     {@code null}
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
   * Deletes an operation, clearing the operation reference of each linked mission. The missions and
   * their participants, finance entries, inventory and refinery orders stay untouched.
   *
   * @param id operation primary key
   * @throws NotFoundException when no match
   */
  @Transactional
  public void deleteOperation(@NotNull UUID id) {
    log.info("Deleting operation with ID: {}", id);
    Operation operation = Entities.require(operationRepository.findById(id), "Operation not found");

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
