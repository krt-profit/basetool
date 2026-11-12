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

import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The eligibility rules of seats and memberships: the silo rule of REQ-ORG-017 and the {@code KRT
 * Member} precondition of REQ-SEC-015. {@link OrgUnitMembershipService} checks them before it
 * writes a seat or a Staffel membership; every notification recipient derived from a membership
 * passes {@link #krtMembersAmong(Collection)}.
 */
@Component
@RequiredArgsConstructor
public class OrgUnitSeatEligibility {

  private final OrgUnitMembershipRepository membershipRepository;
  private final UserRepository userRepository;

  /**
   * Whether the user holds at least one Staffel ({@code SQUADRON}) membership; a silo leader must
   * hold none (REQ-ORG-017).
   *
   * @param userId the user to check; never {@code null}
   * @return {@code true} when the user has a Staffel membership row
   */
  public boolean holdsStaffelMembership(@NotNull UUID userId) {
    return !membershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON).isEmpty();
  }

  /**
   * Whether the user holds a silo-leader rank: {@link MembershipRole#SK_LEAD}, a Bereichsleitung
   * rank or the OL ({@link MembershipRole#isAreaOrOl()}); squadron ranks do not count.
   *
   * @param userId the user to check; never {@code null}
   * @return {@code true} when any of the user's membership rows carries a silo-leader rank
   */
  public boolean holdsSiloLeaderRank(@NotNull UUID userId) {
    return membershipRepository.findAllByIdUserId(userId).stream()
        .anyMatch(m -> m.getRole() == MembershipRole.SK_LEAD || m.getRole().isAreaOrOl());
  }

  /**
   * Refuses a leadership appointment of a user without the stored {@code KRT Member} role
   * (REQ-SEC-015, ADR-0300); only new appointments check, so losing the role later keeps the seat.
   *
   * @param userId the appointee; never {@code null}
   * @throws LeadershipSeatRequiresKrtMemberException if the user does not hold {@code KRT Member}
   */
  public void requireKrtMember(@NotNull UUID userId) {
    if (!holdsKrtMember(userId)) {
      throw new LeadershipSeatRequiresKrtMemberException();
    }
  }

  /**
   * Whether the user holds the stored {@code KRT Member} role, without which none of their
   * memberships confers anything (REQ-SEC-015, ADR-0374).
   *
   * @param userId the user to check; never {@code null}
   * @return {@code true} iff the user holds {@code KRT Member}
   */
  public boolean holdsKrtMember(@NotNull UUID userId) {
    return userRepository.hasAnyRoleCode(userId, List.of(Roles.KRT_MEMBER));
  }

  /**
   * Returns the active units a member may assign to their personal rows (REQ-ORG-004).
   *
   * @param userId the owner of the rows
   * @return the owner's active direct memberships while they hold the stored KRT Member role
   */
  @NotNull
  public Set<UUID> findStampableOrgUnitIds(@NotNull UUID userId) {
    Set<UUID> activeOrgUnitIds = membershipRepository.findActiveOrgUnitIdsByUserId(userId);
    return activeOrgUnitIds.isEmpty() || !holdsKrtMember(userId) ? Set.of() : activeOrgUnitIds;
  }

  /**
   * Keeps those of the given members who hold the stored {@code KRT Member} role, the only members
   * a seat, a membership or a Logistiker or Einsatzleiter flag confers anything on (REQ-SEC-015,
   * ADR-0374); one query for the whole set (REQ-DATA-003).
   *
   * @param userIds the members to check; never {@code null}
   * @return the {@code KRT Member} holders among them; never {@code null}, empty for no member
   */
  @NotNull
  public Set<UUID> krtMembersAmong(@NotNull Collection<UUID> userIds) {
    return userIds.isEmpty()
        ? Set.of()
        : userRepository.findRoleHoldersAmong(userIds, Roles.KRT_MEMBER);
  }
}
