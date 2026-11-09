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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitDto;
import de.greluc.krt.profit.basetool.backend.exchange.internal.dto.ExchangeOrgUnitListDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The org units a member directly belongs to, for exchange clients (REQ-XCH-048). */
@Service
@RequiredArgsConstructor
public class ExchangeOrgUnitService {

  /** Source of the direct memberships, ordered top-down by kind and then by name. */
  private final OrgUnitMembershipQueryService membershipQueryService;

  /**
   * Lists the member's direct memberships of all four kinds, without the leadership cascade.
   *
   * @param member the member the current request acts for
   * @return the org units, ordered Organisationsleitung, Bereich, Staffel, Spezialkommando and then
   *     by name; an empty list when the member belongs to none
   */
  @Transactional(readOnly = true)
  public @NotNull ExchangeOrgUnitListDto orgUnits(@NotNull UUID member) {
    return new ExchangeOrgUnitListDto(
        membershipQueryService.listDirectMembershipOptions(member).stream()
            .map(ExchangeOrgUnitService::toDto)
            .toList());
  }

  /**
   * Lists the direct memberships of several members, in the order {@link #orgUnits(UUID)} gives
   * each.
   *
   * @param members the members
   * @return each member's org units; a member without a direct membership is absent
   */
  @Transactional(readOnly = true)
  public @NotNull Map<UUID, List<ExchangeOrgUnitDto>> orgUnitsOf(
      @NotNull Collection<UUID> members) {
    Map<UUID, List<ExchangeOrgUnitDto>> units = new HashMap<>();
    membershipQueryService
        .listDirectMembershipOptionsByUser(members)
        .forEach((member, options) ->
            units.put(member, options.stream().map(ExchangeOrgUnitService::toDto).toList()));
    return units;
  }

  /**
   * Reads a membership option as the exchange names an org unit.
   *
   * @param option the option
   * @return the org unit
   */
  private static @NotNull ExchangeOrgUnitDto toDto(@NotNull OrgUnitMembershipOptionDto option) {
    return new ExchangeOrgUnitDto(
        option.orgUnitId(), option.orgUnitName(), option.orgUnitShorthand(), option.kind());
  }

  /**
   * Returns the units of which the member is a Logistician, by membership or by the cascade of a
   * Bereich or Organisationsleitung membership (REQ-XCH-043).
   *
   * @param member the member the current request acts for
   * @return the unit ids, possibly empty
   */
  @Transactional(readOnly = true)
  public @NotNull Set<UUID> logisticianUnits(@NotNull UUID member) {
    return membershipQueryService.findLogisticianOrgUnitIds(member);
  }
}
