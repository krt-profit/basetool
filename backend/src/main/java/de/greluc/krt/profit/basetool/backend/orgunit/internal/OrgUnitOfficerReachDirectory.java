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

import de.greluc.krt.profit.basetool.backend.identity.api.OfficerReachDirectory;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The org-unit module's implementation of the identity-owned {@link OfficerReachDirectory}.
 *
 * <p>Any rank other than {@code MEMBER} reaches at least its own unit (an SK lead and the squadron
 * ranks) or the unit it cascades from (Bereich ranks, OL), so a seat held with {@code KRT Member}
 * is reach in at least one unit.
 */
@Component
@RequiredArgsConstructor
public class OrgUnitOfficerReachDirectory implements OfficerReachDirectory {

  private final OrgUnitMembershipRepository membershipRepository;
  private final UserRepository userRepository;

  @Override
  public boolean holdsOfficerReach(@NotNull UUID userId) {
    return membershipRepository.existsLeadershipSeat(userId)
        && userRepository.hasAnyRoleCode(userId, List.of(Roles.KRT_MEMBER));
  }
}
