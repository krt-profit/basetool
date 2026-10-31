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
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
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
            .map(option ->
                new ExchangeOrgUnitDto(
                    option.orgUnitId(),
                    option.orgUnitName(),
                    option.orgUnitShorthand(),
                    option.kind()))
            .toList());
  }

  /**
   * Returns the units whose membership makes the member a Logistician of that unit, without the
   * cascade (REQ-XCH-043).
   *
   * @param member the member the current request acts for
   * @return the unit ids, possibly empty
   */
  @Transactional(readOnly = true)
  public @NotNull Set<UUID> logisticianUnits(@NotNull UUID member) {
    return membershipQueryService.findLogisticianOrgUnitIds(member);
  }
}
