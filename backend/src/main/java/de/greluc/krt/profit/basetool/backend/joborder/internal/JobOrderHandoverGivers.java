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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides who may be named as handing a material or item handover over (REQ-ORDERS-079): an active
 * account that is a direct member of the order's processing unit.
 */
@Service
@RequiredArgsConstructor
public class JobOrderHandoverGivers {

  private static final Comparator<JobOrderHandoverGiverDto> BY_HANDLE =
      Comparator.comparing(JobOrderHandoverGiverDto::handle, String.CASE_INSENSITIVE_ORDER);

  private final JobOrderRepository jobOrderRepository;
  private final UserRepository userRepository;
  private final OrgUnitMembershipQueryService membershipQueryService;

  /**
   * The member stamped onto a handover and the handle snapshot recorded beside them.
   *
   * @param user the named member
   * @param handle the member's effective name at recording time
   */
  record Giver(@NotNull User user, @NotNull String handle) {}

  /**
   * Lists the members of the order's processing unit who may be named, ordered by handle.
   *
   * @param jobOrderId the order
   * @return the active direct members of the processing unit; empty when the unit has none
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the order is
   *     unknown
   */
  @Transactional(readOnly = true)
  @NotNull
  public List<JobOrderHandoverGiverDto> candidates(@NotNull UUID jobOrderId) {
    JobOrder order =
        Entities.require(
            jobOrderRepository.findById(jobOrderId), () -> "JobOrder not found: " + jobOrderId);
    Set<UUID> members = members(order);
    if (members.isEmpty()) {
      return List.of();
    }
    return userRepository.findActiveReferencesByIds(members).stream()
        .map(user -> new JobOrderHandoverGiverDto(user.id(), user.effectiveName()))
        .sorted(BY_HANDLE)
        .toList();
  }

  /**
   * Resolves the named member for a handover being recorded on {@code order}.
   *
   * @param order the order whose processing unit the member must belong to
   * @param userId the named member
   * @return the member with their handle snapshot
   * @throws BadRequestException when the account is not active or not a direct member of the
   *     order's processing unit
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @NotNull
  Giver require(@NotNull JobOrder order, @NotNull UUID userId) {
    if (!members(order).contains(userId)) {
      throw new BadRequestException(JobOrderStockErrors.HANDING_OVER_NOT_MEMBER);
    }
    User user =
        userRepository
            .findPlainById(userId)
            .filter(User::isActiveAccount)
            .orElseThrow(
                () -> new BadRequestException(JobOrderStockErrors.HANDING_OVER_NOT_MEMBER));
    return new Giver(user, user.getEffectiveName());
  }

  private @NotNull Set<UUID> members(@NotNull JobOrder order) {
    OrgUnit processing = order.getResponsibleOrgUnit();
    return processing == null
        ? Set.of()
        : membershipQueryService.findDirectMemberUserIds(processing.getId());
  }
}
