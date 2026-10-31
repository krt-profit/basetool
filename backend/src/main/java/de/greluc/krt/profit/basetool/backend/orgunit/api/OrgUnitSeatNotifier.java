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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RolesChangedObserver;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.events.OrgNotices;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
 * Tells the admins when a leadership seat holder lacks the {@code KRT Member} role, and clears that
 * notice once the member holds the role again or no seat any more (REQ-ORG-029).
 */
@Service
@RequiredArgsConstructor
public class OrgUnitSeatNotifier implements RolesChangedObserver {

  private static final String MISSING_KRT_MEMBER = "MISSING_KRT_MEMBER";
  private static final String APPOINTED = "APPOINTED";
  private static final String REMOVED = "REMOVED";
  private static final String ROLES_RECONCILED = "ROLES_RECONCILED";
  private static final String UNKNOWN_LABEL = "—";

  private final OrgUnitMembershipRepository membershipRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final UserRepository userRepository;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Checks a member after a seat was filled or vacated and reports a seat holder without {@code KRT
   * Member}; otherwise the member's earlier notice goes.
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
    if (!membershipRepository.existsLeadershipSeat(userId)
        || userRepository.hasAnyRoleCode(userId, List.of(Roles.KRT_MEMBER))) {
      eventPublisher.publishEvent(OrgNotices.leadershipRolesAgree(userId));
      return;
    }
    publishMissingKrtMember(userId, unitName, appointed ? APPOINTED : REMOVED, rank, actorSub);
  }

  /**
   * Reports a seat holder whose freshly reconciled roles lack {@code KRT Member}, naming one of
   * their seats; a member who holds the role or no seat has their notice cleared.
   *
   * @param userId the member
   * @param roles the member's roles after the reconciliation
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void onRolesChanged(@NotNull UUID userId, @NotNull Set<Role> roles) {
    boolean krtMember =
        roles.stream().anyMatch(role -> Roles.KRT_MEMBER.equalsIgnoreCase(role.getCode()));
    Optional<OrgUnitMembership> seat = krtMember ? Optional.empty() : firstSeat(userId);
    if (seat.isEmpty()) {
      eventPublisher.publishEvent(OrgNotices.leadershipRolesAgree(userId));
      return;
    }
    String unitName =
        orgUnitRepository
            .findById(seat.get().getId().getOrgUnitId())
            .map(OrgUnitLabels::shorthandOrName)
            .orElse(UNKNOWN_LABEL);
    publishMissingKrtMember(userId, unitName, ROLES_RECONCILED, seat.get().getRole(), null);
  }

  private Optional<OrgUnitMembership> firstSeat(UUID userId) {
    return membershipRepository.findAllByIdUserId(userId).stream()
        .filter(membership -> membership.getRole() != MembershipRole.MEMBER)
        .min(Comparator.comparing(OrgUnitMembership::getRole));
  }

  private void publishMissingKrtMember(
      UUID userId, String unitName, String seatCode, MembershipRole rank, @Nullable UUID actorSub) {
    String member =
        userRepository.findPlainById(userId).map(User::getEffectiveName).orElse(UNKNOWN_LABEL);
    eventPublisher.publishEvent(
        OrgNotices.leadershipRoleMismatch(
            userId, member, unitName, seatCode, rank.name(), MISSING_KRT_MEMBER, actorSub));
  }
}
