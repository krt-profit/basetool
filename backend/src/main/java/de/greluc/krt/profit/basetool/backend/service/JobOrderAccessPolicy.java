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

import de.greluc.krt.profit.basetool.backend.inventory.api.EarmarkTargetPolicy;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The job-order module's access policy: the read and edit gates of an order, of its owner panels
 * and of the requester side, evaluated on the scope kernel (plan §5.4, ADR-0236). It also decides
 * the Lager's earmarked stock of an order ({@link EarmarkTargetPolicy}).
 *
 * <p>Invoked from SpEL as {@code @jobOrderAccessPolicy}. Unknown ids are refused. The queue
 * capability itself ({@code canViewJobOrders}) stays with the scope kernel, which the identity and
 * exchange modules ask too. Read-only transactional.
 */
@Service(JobOrderAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderAccessPolicy implements EarmarkTargetPolicy {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "jobOrderAccessPolicy";

  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelper;
  private final JobOrderRepository jobOrderRepository;
  private final JobOrderHandoverRepository jobOrderHandoverRepository;
  private final JobOrderItemHandoverRepository jobOrderItemHandoverRepository;

  /**
   * Checks whether the caller may read job order {@code jobOrderId}: the caller must reach the
   * queue, then an order without a responsible unit or of a Spezialkommando is public, any other
   * order is visible within the caller's read scope (REQ-ORG-003).
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
  public boolean canSeeJobOrder(@NotNull JobOrder order) {
    if (!ownerScopeService.canViewJobOrders()) {
      return false;
    }
    OrgUnit responsible = order.getResponsibleOrgUnit();
    if (responsible == null || responsible.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
      return true;
    }
    return ownerScopeService.canSeeSquadron(responsible.getId());
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
        .map(responsible -> ownerScopeService.canSeeSquadron(responsible.getId()))
        .orElse(false);
  }

  /**
   * Checks whether the caller may see who owns the Lager stock linked to an order: only within the
   * read scope of the order's responsible unit.
   *
   * @param jobOrderId the order to inspect; never {@code null}
   * @return {@code true} iff the caller may see the stock owners
   */
  public boolean canSeeJobOrderInventoryOwners(@NotNull UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .map(JobOrder::getResponsibleOrgUnit)
        .map(responsible -> ownerScopeService.canSeeSquadron(responsible.getId()))
        .orElse(false);
  }

  /**
   * Checks whether the caller may edit job order {@code jobOrderId}: like {@link
   * #canSeeJobOrder(UUID)} with the edit scope.
   *
   * @param jobOrderId the order to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the order
   */
  public boolean canEditJobOrder(@NotNull UUID jobOrderId) {
    return jobOrderRepository.findById(jobOrderId).map(this::canEditJobOrderRow).orElse(false);
  }

  /**
   * Checks whether the caller may read job order {@code jobOrderId} as a member of the org unit
   * that placed it (REQ-ORDERS-023), independent of the profit eligibility.
   *
   * @param jobOrderId the order to inspect; never {@code null}
   * @return {@code true} iff the caller belongs to the requesting org unit
   */
  public boolean canSeeJobOrderAsRequester(@NotNull UUID jobOrderId) {
    return jobOrderRepository.findById(jobOrderId).map(this::isOrderRequesterRow).orElse(false);
  }

  /**
   * Checks whether the caller may edit job order {@code jobOrderId} as its requester: a member of
   * the requesting org unit, while nothing has been delivered yet (REQ-ORDERS-023).
   *
   * @param jobOrderId the order to inspect; never {@code null}
   * @return {@code true} iff the caller may edit the order as its requester
   */
  public boolean canEditJobOrderAsRequester(@NotNull UUID jobOrderId) {
    return jobOrderRepository
        .findById(jobOrderId)
        .map(o -> isOrderRequesterRow(o) && !orderHasAnyDelivery(o.getId()))
        .orElse(false);
  }

  @Override
  public boolean mayEditJobOrderEarmarks(@NotNull UUID jobOrderId) {
    return authHelper.isLogisticianOrAbove() && canEditJobOrder(jobOrderId);
  }

  private boolean canEditJobOrderRow(@NotNull JobOrder order) {
    if (!ownerScopeService.canViewJobOrders()) {
      return false;
    }
    OrgUnit responsible = order.getResponsibleOrgUnit();
    if (responsible == null || responsible.getKind() == OrgUnitKind.SPECIAL_COMMAND) {
      return true;
    }
    return ownerScopeService.canEditSquadron(responsible.getId());
  }

  private boolean isOrderRequesterRow(@NotNull JobOrder order) {
    OrgUnit requesting = order.getRequestingOrgUnit();
    return requesting != null && ownerScopeService.currentUserIsMemberOfOrgUnit(requesting.getId());
  }

  private boolean orderHasAnyDelivery(@NotNull UUID jobOrderId) {
    return jobOrderHandoverRepository.existsByJobOrderId(jobOrderId)
        || jobOrderItemHandoverRepository.existsByJobOrderId(jobOrderId);
  }
}
