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

package de.greluc.krt.profit.basetool.backend.scope.api;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserPeerRedactionScope;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The grant-based {@link UserPeerRedactionScope}: a user is unredacted for the caller when they
 * belong to a unit where the caller holds the Logistician grant; admins read everyone unredacted
 * (REQ-SEC-007).
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GrantBasedUserPeerRedactionScope implements UserPeerRedactionScope {

  private final OrgUnitMembershipRepository membershipRepository;
  private final OwnerScopeService ownerScopeService;
  private final AuthHelperService authHelperService;

  @Override
  public @NotNull Set<UUID> usersWithUnredactedRead(@NotNull Collection<UUID> userIds) {
    if (userIds.isEmpty()) {
      return Set.of();
    }
    if (authHelperService.isAdmin()) {
      return Set.copyOf(userIds);
    }
    Map<UUID, Boolean> grantByUnit = new HashMap<>();
    Set<UUID> lifted = new HashSet<>();
    for (OrgUnitMembership membership : membershipRepository.findAllByIdUserIdIn(userIds)) {
      UUID unitId = membership.getId().getOrgUnitId();
      boolean granted =
          grantByUnit.computeIfAbsent(
              unitId, id -> ownerScopeService.hasRoleInOrgUnit(id, Roles.LOGISTICIAN));
      if (granted) {
        lifted.add(membership.getId().getUserId());
      }
    }
    return lifted;
  }
}
