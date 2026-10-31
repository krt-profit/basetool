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
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The eligibility rules {@link OrgUnitMembershipService} checks before it writes a seat or a
 * Staffel membership: the silo rule of REQ-ORG-017 and the {@code KRT Member} precondition of
 * REQ-SEC-015.
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
    if (!userRepository.hasAnyRoleCode(userId, List.of(Roles.KRT_MEMBER))) {
      throw new LeadershipSeatRequiresKrtMemberException();
    }
  }
}
