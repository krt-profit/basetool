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

import de.greluc.krt.profit.basetool.backend.identity.api.RolesChangedObserver;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.orgunit.api.events.OrgNotices;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells the admins when a leadership change leaves a member's OFFICER role out of step with their
 * seats, and clears that notice once the roles fit again (REQ-ORG-029). The OFFICER role is granted
 * by hand next to a seat, so the two can drift.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitSeatNotifier implements RolesChangedObserver {

  private final OrgUnitMembershipRepository membershipRepository;
  private final UserRepository userRepository;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Checks a member after a seat was filled or vacated and publishes the mismatch, if any; when the
   * roles fit, the member's earlier mismatch notice goes.
   *
   * @param userId the member
   * @param unitName the unit's shorthand or name
   * @param rank the seat that was filled, or the seat that was vacated
   * @param appointed whether the seat was filled
   * @param actorSub the member who changed the seat, or {@code null}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void seatChanged(
      @NotNull UUID userId,
      @NotNull String unitName,
      @NotNull MembershipRole rank,
      boolean appointed,
      @Nullable UUID actorSub) {
    String mismatch =
        mismatch(
            membershipRepository.existsLeadershipSeat(userId),
            userRepository.hasAnyRoleCode(userId, List.of(Roles.OFFICER)),
            userRepository.hasAnyRoleCode(userId, List.of(Roles.ADMIN)));
    if (mismatch == null) {
      eventPublisher.publishEvent(OrgNotices.leadershipRolesAgree(userId));
      return;
    }
    String member = userRepository.findPlainById(userId).map(User::getEffectiveName).orElse("—");
    eventPublisher.publishEvent(
        OrgNotices.leadershipRoleMismatch(
            userId,
            member,
            unitName,
            appointed ? "APPOINTED" : "REMOVED",
            rank.name(),
            mismatch,
            actorSub));
  }

  /**
   * Clears the member's mismatch notice when their freshly reconciled roles fit their seats; a
   * member whose roles still disagree keeps it.
   *
   * @param userId the member
   * @param roles the member's roles after the reconciliation
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void onRolesChanged(@NotNull UUID userId, @NotNull Set<Role> roles) {
    boolean officer =
        roles.stream().anyMatch(role -> Roles.OFFICER.equalsIgnoreCase(role.getCode()));
    boolean admin = roles.stream().anyMatch(role -> Roles.ADMIN.equalsIgnoreCase(role.getCode()));
    if (mismatch(membershipRepository.existsLeadershipSeat(userId), officer, admin) == null) {
      eventPublisher.publishEvent(OrgNotices.leadershipRolesAgree(userId));
    }
  }

  @Nullable
  private static String mismatch(boolean holdsSeat, boolean officer, boolean admin) {
    if (admin) {
      return null;
    }
    if (holdsSeat && !officer) {
      return "MISSING_OFFICER";
    }
    if (!holdsSeat && officer) {
      return "SURPLUS_OFFICER";
    }
    return null;
  }
}
