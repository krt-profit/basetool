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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderParticipation;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderSupportDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.StaffelParticipationDto;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyService;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exposes current and retained Staffel support on the central order. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobOrderSupportService {
  private final JobOrderRepository orders;
  private final OrgUnitRepository units;
  private final ProfitHierarchyService profitHierarchy;
  private final JobOrderResponsibilityGuard responsibility;
  private final MaterialClaimAuthority authority;
  private final JobOrderParticipationRepository participations;
  private final JobOrderParticipationCommands commands;
  private final JobOrderEditor editor;
  private final JobOrderAccessPolicy accessPolicy;

  /**
   * Projects support history and eligible current actions for a readable order.
   *
   * @param orderId the readable order
   * @return retained participations and the current caller's enrollment choices
   */
  public JobOrderSupportDto support(UUID orderId) {
    JobOrder order =
        Entities.require(orders.findById(orderId), () -> "Order not found: " + orderId);
    if (!accessPolicy.canSeeJobOrder(order)) {
      throw new AccessDeniedException("orders.claim.provision.forbidden");
    }
    Set<UUID> eligible = profitHierarchy.eligibleUnitIds();
    var rows = participations.findByJobOrderIdOrderById(orderId);
    boolean valid = responsibility.isValid(order);
    var views = rows.stream().map(row -> participationView(order, row, eligible, valid)).toList();
    return new JobOrderSupportDto(
        views,
        valid && order.getStatus().isActive()
            ? enrollmentOptions(order, rows, eligible)
            : List.of());
  }

  /**
   * Enrolls one eligible Staffel using current order or own-Staffel authority.
   *
   * @param orderId the order receiving support
   * @param unitId the eligible supporting Staffel
   */
  @Transactional
  public void enroll(UUID orderId, UUID unitId) {
    editor.editWithoutProjection(
        orderId,
        JobOrderGate.SUPPORT,
        _ ->
            JobOrderEditPlan.of(order -> {
              authority.assertCanManage(order, unitId);
              if (!order.getStatus().isActive()) {
                throw new BadRequestException("orders.claim.provision.not_open");
              }
              OrgUnit unit =
                  units
                      .findById(unitId)
                      .filter(candidate ->
                          eligible(order, candidate, profitHierarchy.eligibleUnitIds()))
                      .orElseThrow(
                          () -> new BadRequestException("orders.claim.claimingUnit.ineligible"));
              commands.enroll(order, unit, JobOrderParticipationCommands.Enrollment.EXPLICIT);
              return JobOrderEdit.unchanged(order);
            }));
  }

  /**
   * Ends the observed participation using current responsible or own-Staffel authority.
   *
   * @param orderId the order receiving support
   * @param unitId the supporting Staffel
   * @param version the observed participation version
   */
  @Transactional
  public void withdraw(UUID orderId, UUID unitId, Long version) {
    editor.editWithoutProjection(
        orderId,
        JobOrderGate.SUPPORT,
        _ ->
            JobOrderEditPlan.of(order -> {
              authority.assertCanManage(order, unitId);
              commands.withdraw(order, unitId, version);
              return JobOrderEdit.unchanged(order);
            }));
  }

  private StaffelParticipationDto participationView(
      JobOrder order, JobOrderParticipation row, Set<UUID> eligible, boolean writable) {
    OrgUnit unit = row.getClaimingOrgUnit();
    boolean active = row.isActive() && eligible(order, unit, eligible);
    return new StaffelParticipationDto(
        row.getId(),
        option(unit),
        row.getVersion(),
        active,
        writable && active && authority.canManage(order, unit.getId()));
  }

  private List<OrgUnitMembershipOptionDto> enrollmentOptions(
      JobOrder order, List<JobOrderParticipation> rows, Set<UUID> eligible) {
    Set<UUID> active =
        rows.stream()
            .filter(JobOrderParticipation::isActive)
            .map(row -> row.getClaimingOrgUnit().getId())
            .collect(Collectors.toSet());
    return units.findAllById(eligible).stream()
        .filter(unit -> eligible(order, unit, eligible))
        .filter(unit -> !active.contains(unit.getId()))
        .filter(unit -> authority.canManage(order, unit.getId()))
        .sorted(Comparator.comparing(OrgUnit::getName, String.CASE_INSENSITIVE_ORDER))
        .map(JobOrderSupportService::option)
        .toList();
  }

  private static boolean eligible(JobOrder order, OrgUnit unit, Set<UUID> eligibleUnits) {
    return unit.getKind() == OrgUnitKind.SQUADRON
        && eligibleUnits.contains(unit.getId())
        && !Objects.equals(unit.getId(), OrgUnit.idOf(order.getResponsibleOrgUnit()));
  }

  private static OrgUnitMembershipOptionDto option(OrgUnit unit) {
    return new OrgUnitMembershipOptionDto(
        unit.getId(), unit.getName(), unit.getShorthand(), unit.getKind());
  }
}
