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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.EarmarkTargetPolicy;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuthority;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderAuthorityFlags;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderVisibility;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves complete order reading, independent producer visibility and current write capabilities.
 */
@Service(JobOrderAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderAccessPolicy
    implements EarmarkTargetPolicy, JobOrderVisibility, JobOrderAuthority {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "jobOrderAccessPolicy";

  private final RequestScopeResolver requestScopeResolver;
  private final AccessGateService accessGateService;
  private final AuthHelperService authHelper;
  private final JobOrderRepository jobOrderRepository;
  private final JobOrderResponsibilityGuard responsibilityGuard;
  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  private final JobOrderUnitReadHistoryRepository unitReadHistory;
  private final JobOrderParticipationRepository participations;

  /**
   * Checks complete current order reading, including personal and observed unit relationships
   * (REQ-ORDERS-052).
   *
   * @param jobOrderId the order to inspect; never {@code null}
   * @return {@code true} iff the caller may read the order
   */
  public boolean canSeeJobOrder(@NotNull UUID jobOrderId) {
    return jobOrderRepository.findById(jobOrderId).map(this::canSeeJobOrder).orElse(false);
  }

  /**
   * Entity overload of {@link #canSeeJobOrder(UUID)} for callers that already hold the order.
   *
   * @param order the order to inspect; never {@code null}
   * @return {@code true} iff the caller may read the order
   */
  @Override
  public boolean canSeeJobOrder(@NotNull JobOrder order) {
    return isAssigneeRow(order)
        || (!authHelper.isAdmin() && requestScopeResolver.canViewJobOrders())
        || requestScopeResolver.currentScopePredicate().adminAllScope()
        || (order.getResponsibleOrgUnit() != null
            && accessGateService.canSeeSquadronAsGrantee(
                order.getResponsibleOrgUnit().getId(), Roles.LOGISTICIAN))
        || isOrderRequesterRow(order)
        || hasHistoricalUnitRead(order);
  }

  /**
   * Checks whether the caller sees the order within the tenancy scope, without the assignee grant:
   * the caller must reach the queue, then an order without a responsible unit or of a
   * Spezialkommando is public, any other order is visible within the caller's read scope
   * (REQ-ORG-003).
   *
   * @param order the order to inspect; never {@code null}
   * @return {@code true} iff the order is in the caller's read scope
   */
  @Override
  public boolean canSeeJobOrderInScope(@NotNull JobOrder order) {
    if (!requestScopeResolver.canViewJobOrders()) {
      return false;
    }
    OrgUnit responsible = order.getResponsibleOrgUnit();
    if (responsible == null || responsible.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
      return true;
    }
    return accessGateService.canSeeSquadronAsGrantee(responsible.getId(), Roles.LOGISTICIAN);
  }

  /**
   * Checks whether the caller may see who owns the blueprints an order needs: only within the read
   * scope of the order's responsible unit.
   *
   * @param jobOrderId the order to inspect; never {@code null}
   * @return {@code true} iff the caller may see the blueprint owners
   */
  public boolean canSeeJobOrderBlueprintOwners(@NotNull UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .map(JobOrder::getResponsibleOrgUnit)
        .map(responsible -> accessGateService.canSeeSquadron(responsible.getId()))
        .orElse(false);
  }

  /**
   * Checks the current responsible-unit writer and valid responsibility for processing.
   *
   * @param jobOrderId the order to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the order
   */
  public boolean canEditJobOrder(@NotNull UUID jobOrderId) {
    return jobOrderRepository.findById(jobOrderId).map(this::canEditJobOrderRow).orElse(false);
  }

  /**
   * Decides whether the caller passes {@code gate} on the already-loaded order.
   *
   * @param order the loaded order the edit targets
   * @param gate the access the edit demands
   * @return {@code true} iff the caller passes the gate
   */
  public boolean canEdit(@NotNull JobOrder order, @NotNull JobOrderGate gate) {
    if (gate == JobOrderGate.TRANSFER) {
      return canTransferResponsibility(order);
    }
    if (responsibilityInvalid(order)) {
      return false;
    }
    return switch (gate) {
      case TRANSFER -> canTransferResponsibility(order);
      case EDIT -> canEditJobOrderRow(order);
      case REQUESTER -> isRequesterWriter(order) && !orderHasAnyDelivery(order.getId());
    };
  }

  /**
   * Checks the current requesting-unit writer while the delivery restriction permits an edit
   * (REQ-ORDERS-023).
   *
   * @param jobOrderId the order to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the order as its requester
   */
  public boolean canEditJobOrderAsRequester(@NotNull UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .map(o -> canEdit(o, JobOrderGate.REQUESTER))
        .orElse(false);
  }

  /**
   * Checks whether the caller may add, remove or annotate other users' assignee entries of job
   * order {@code jobOrderId}: a Logistician of the order's processing unit (the contextual
   * Logistician grant of that unit, admins always) within its edit scope (REQ-ORDERS-014). A
   * Logistician grant in another unit never qualifies, nor does the assignee grant
   * (REQ-ORDERS-047).
   *
   * @param jobOrderId the order to inspect; never {@code null}
   * @return {@code true} iff the caller may manage the order's assignees
   */
  public boolean canManageAssignees(@NotNull UUID jobOrderId) {
    return jobOrderRepository.findById(jobOrderId).map(this::canManageAssignees).orElse(false);
  }

  /**
   * Entity overload of {@link #canManageAssignees(UUID)} for callers that already hold the order.
   *
   * @param order the order to inspect; never {@code null}
   * @return {@code true} iff the caller may manage the order's assignees
   */
  public boolean canManageAssignees(@NotNull JobOrder order) {
    return canEditJobOrderRow(order);
  }

  @Override
  public boolean mayEditJobOrderEarmarks(@NotNull UUID jobOrderId) {
    return canEditJobOrder(jobOrderId);
  }

  @Override
  public Map<UUID, JobOrderAuthorityFlags> authorityFor(@NotNull Collection<JobOrder> orders) {
    if (orders.isEmpty()) {
      return Map.of();
    }
    Set<UUID> validIds = responsibilityGuard.validOrderIds(orders);
    Set<UUID> requesterWriters = new java.util.LinkedHashSet<>();
    for (JobOrder order : orders) {
      if (validIds.contains(order.getId()) && isRequesterWriter(order)) {
        requesterWriters.add(order.getId());
      }
    }
    Set<UUID> delivered =
        requesterWriters.isEmpty()
            ? Set.of()
            : jobOrderRepository.findIdsWithAnyDelivery(requesterWriters);
    Map<UUID, JobOrderAuthorityFlags> flags = new LinkedHashMap<>();
    for (JobOrder order : orders) {
      boolean mayTransfer = canTransferResponsibility(order);
      boolean valid = validIds.contains(order.getId());
      flags.put(
          order.getId(),
          new JobOrderAuthorityFlags(
              !valid,
              valid && mayTransfer,
              mayTransfer,
              requesterWriters.contains(order.getId()) && !delivered.contains(order.getId())));
    }
    return Collections.unmodifiableMap(flags);
  }

  @Override
  public boolean responsibilityInvalid(@NotNull JobOrder order) {
    return !responsibilityGuard.isValid(order);
  }

  @Override
  public boolean canTransferResponsibility(@NotNull JobOrder order) {
    return accessGateService.canActAsLogisticianInOwner(
        OrgUnit.idOf(order.getResponsibleOrgUnit()));
  }

  /**
   * Checks the current writer of either proposed side before creation.
   *
   * @param responsible the proposed responsible unit
   * @param requesting the requesting unit
   * @return whether the caller currently writes either side
   */
  public boolean canCreate(OrgUnit responsible, OrgUnit requesting) {
    return accessGateService.canActAsLogisticianIn(responsible.getId())
        || accessGateService.canActAsLogisticianIn(requesting.getId());
  }

  private boolean hasHistoricalUnitRead(@NotNull JobOrder order) {
    ScopePredicate scope = requestScopeResolver.currentScopePredicateGranting(Roles.LOGISTICIAN);
    Set<UUID> units =
        scope.activeOrgUnitId() == null
            ? scope.memberOrgUnitIds()
            : Set.of(scope.activeOrgUnitId());
    return !units.isEmpty()
        && (unitReadHistory.existsByJobOrderIdAndOrgUnitIdIn(order.getId(), units)
            || participations.existsByJobOrderIdAndClaimingOrgUnitIdIn(order.getId(), units));
  }

  private boolean isAssigneeRow(@NotNull JobOrder order) {
    return authHelper
        .currentUserId()
        .map(callerId ->
            order.getAssignees().stream()
                .anyMatch(a -> a.getUser() != null && callerId.equals(a.getUser().getId())))
        .orElse(false);
  }

  /**
   * The Logistician edit gate: a grant in the responsible unit, an order without one is admin's.
   */
  private boolean canEditJobOrderRow(@NotNull JobOrder order) {
    if (responsibilityInvalid(order)) {
      return false;
    }
    OrgUnit responsible = order.getResponsibleOrgUnit();
    return accessGateService.canActAsLogisticianInOwner(
        responsible == null ? null : responsible.getId());
  }

  private boolean isOrderRequesterRow(@NotNull JobOrder order) {
    OrgUnit requesting = order.getRequestingOrgUnit();
    return requesting != null
        && accessGateService.canSeeSquadronAsGrantee(requesting.getId(), Roles.LOGISTICIAN);
  }

  private boolean isRequesterWriter(@NotNull JobOrder order) {
    OrgUnit requesting = order.getRequestingOrgUnit();
    return requesting != null && accessGateService.canActAsLogisticianIn(requesting.getId());
  }

  private boolean orderHasAnyDelivery(@NotNull UUID jobOrderId) {
    return jobOrderHandoverRepository.existsByJobOrderId(jobOrderId)
        || jobOrderItemHandoverRepository.existsByJobOrderId(jobOrderId);
  }
}
