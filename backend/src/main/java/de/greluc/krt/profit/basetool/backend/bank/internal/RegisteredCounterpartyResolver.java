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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Resolves deletion-proof snapshots of registered counterparties and their selected membership. */
public final class RegisteredCounterpartyResolver {
  private RegisteredCounterpartyResolver() {}

  /**
   * Resolves the member and validates an optional direct org-unit membership.
   *
   * @param users the member repository
   * @param memberships the direct membership lookup
   * @param userId the registered counterparty
   * @param orgUnitId the selected membership, or null
   * @return the counterparty snapshot
   * @throws BadRequestException if the selected unit is not a direct membership
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException if the user is absent
   */
  public static @NotNull CounterpartySnapshot resolve(
      @NotNull UserRepository users,
      @NotNull OrgUnitMembershipQueryService memberships,
      @NotNull UUID userId,
      @Nullable UUID orgUnitId) {
    User user = Entities.require(users.findById(userId), "Counterparty user not found");
    if (orgUnitId == null) {
      return new CounterpartySnapshot(user.getId(), user.getEffectiveName(), null, null);
    }
    OrgUnitMembershipOptionDto membership =
        memberships.listDirectMembershipOptions(userId).stream()
            .filter(option -> option.orgUnitId().equals(orgUnitId))
            .findFirst()
            .orElseThrow(() ->
                new BadRequestException(
                    "The selected org unit is not one of the counterparty's memberships"));
    return new CounterpartySnapshot(
        user.getId(), user.getEffectiveName(), membership.orgUnitId(), membership.orgUnitName());
  }
}
