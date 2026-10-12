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

import de.greluc.krt.profit.basetool.backend.identity.api.MembershipOperations;
import de.greluc.krt.profit.basetool.backend.identity.api.StaffelAssignment;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The org-unit module's implementation of the identity-owned {@link MembershipOperations}: each
 * method forwards to {@link OrgUnitMembershipService} or {@link OrgUnitMembershipQueryService}
 * unchanged. It declares no transaction of its own, so the callee's propagation applies.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitMembershipOperations implements MembershipOperations<OrgUnitMembership> {

  private final OrgUnitMembershipService membershipService;
  private final OrgUnitMembershipQueryService membershipQueryService;

  @Override
  public @NotNull List<UUID> findStaffelMembershipOrgUnitIds(@NotNull UUID userId) {
    return membershipQueryService.findStaffelMembershipOrgUnitIds(userId);
  }

  @Override
  public @NotNull List<OrgUnitMembership> findAllMembershipsForUser(@NotNull UUID userId) {
    return membershipQueryService.findAllMembershipsForUser(userId);
  }

  @Override
  public void reconcileStaffelMemberships(
      @NotNull User user, @NotNull List<StaffelAssignment> desired) {
    membershipService.reconcileStaffelMemberships(
        user,
        desired.stream()
            .map(
                a ->
                    new MembershipDeltaRequest.StaffelChange(
                        a.squadronId(), a.isLogistician(), a.isMissionManager()))
            .toList());
  }

  @Override
  public @NotNull OrgUnitMembership addMember(
      @NotNull UUID specialCommandId, @NotNull UUID userId) {
    return membershipService.addMember(specialCommandId, userId);
  }

  @Override
  public void removeMember(@NotNull UUID specialCommandId, @NotNull UUID userId) {
    membershipService.removeMember(specialCommandId, userId);
  }

  @Override
  public @NotNull OrgUnitMembership patchFlags(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @Nullable Boolean isLogistician,
      @Nullable Boolean isMissionManager,
      @Nullable Long version) {
    return membershipService.patchFlags(
        specialCommandId,
        userId,
        new MembershipFlagsPatchRequest(isLogistician, isMissionManager, version));
  }
}
