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

package de.greluc.krt.profit.basetool.backend.orgunit.internal;

import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.orgunit.api.KommandoGroup;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsReassignment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The org-unit module's membership part of the account merge: the memberships follow the member, a
 * unit both accounts belong to is kept once.
 */
@Component
public class OrgUnitMembershipUserReassignment extends OwnedRowsReassignment {

  static final int POSITION = 60;
  private static final int MAX_STAFFELN = 2;

  private final OrgUnitMembershipRepository membershipRepository;

  /**
   * Declares the module's columns.
   *
   * @param mover the engine that runs the statements
   * @param membershipRepository the memberships to check before the move
   */
  public OrgUnitMembershipUserReassignment(
      @NotNull OwnedRowsMover mover, @NotNull OrgUnitMembershipRepository membershipRepository) {
    super(
        mover,
        POSITION,
        List.of(OwnedRows.deduped("org_unit_membership", "user_id", "org_unit_id")),
        List.of());
    this.membershipRepository = membershipRepository;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void assertMergeable(@NotNull UUID source, @NotNull UUID target) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      membershipRepository.lockMembershipsForMerge(List.of(source, target));
    }
    Map<UUID, OrgUnitMembership> merged = new HashMap<>();
    for (OrgUnitMembership row : membershipRepository.findAllByIdUserId(target)) {
      merged.put(row.getId().getOrgUnitId(), row);
    }
    for (OrgUnitMembership row : membershipRepository.findAllByIdUserId(source)) {
      OrgUnitMembership survivor = merged.putIfAbsent(row.getId().getOrgUnitId(), row);
      if (survivor != null && losesMembershipDetails(row, survivor)) {
        throw new BusinessConflictException(
            "The source membership carries a different rank, group or capability");
      }
    }
    long staffeln =
        merged.values().stream().filter(row -> row.getKind() == OrgUnitKind.SQUADRON).count();
    if (staffeln > MAX_STAFFELN
        || (staffeln > 0
            && merged.values().stream().anyMatch(row -> excludesStaffel(row.getRole())))) {
      throw new BusinessConflictException(
          "The merged memberships would exceed the Staffel or leadership limits");
    }
  }

  private static boolean losesMembershipDetails(
      OrgUnitMembership source, OrgUnitMembership survivor) {
    return (source.getRole() != MembershipRole.MEMBER && source.getRole() != survivor.getRole())
        || (source.isLogistician() && !survivor.isLogistician())
        || (source.isMissionManager() && !survivor.isMissionManager())
        || (source.getKommandoGroup() != null
            && !Objects.equals(
                groupId(source.getKommandoGroup()), groupId(survivor.getKommandoGroup())));
  }

  private static @Nullable UUID groupId(@Nullable KommandoGroup group) {
    return group == null ? null : group.getId();
  }

  private static boolean excludesStaffel(MembershipRole role) {
    return role == MembershipRole.SK_LEAD || role == MembershipRole.OL_MEMBER || role.isAreaRank();
  }
}
