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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyTabAccess;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides the supply tabs of the caller (REQ-UI-026): „Eingehende Spenden" by the units the
 * incoming-donations page lists, „Statistik" by the gate of {@link SupplyStatsService#stats(int)}.
 */
@Service
@RequiredArgsConstructor
public class SupplyTabAccessService implements SupplyTabAccess {

  private final SupplySettingsService settingsService;
  private final SupplyAccessPolicy accessPolicy;
  private final SupplyUnitSettingRepository settingRepository;
  private final OrgUnitRepository orgUnitRepository;

  /**
   * Decides the tabs: „Eingehende Spenden" when the caller manages an active unit that accepts
   * donations, „Statistik" by {@link SupplyAccessPolicy#canReadStats()}; none while the surface is
   * off.
   *
   * @return the tabs the caller may open
   */
  @Override
  @Transactional(readOnly = true)
  public @NotNull SupplyTabGrants current() {
    if (!settingsService.isEnabled()) {
      return SupplyTabGrants.NONE;
    }
    return new SupplyTabGrants(managesAnAcceptingUnit(), accessPolicy.canReadStats());
  }

  /**
   * Whether the caller manages an active unit that accepts donations; an admin manages every unit.
   *
   * @return {@code true} when the incoming-donations page lists at least one unit for the caller
   */
  private boolean managesAnAcceptingUnit() {
    List<UUID> accepting =
        settingRepository.findByAcceptsDonationsTrue().stream()
            .map(SupplyUnitSetting::getId)
            .toList();
    if (accepting.isEmpty()) {
      return false;
    }
    List<UUID> reachable = accepting;
    if (!accessPolicy.isAdmin()) {
      Set<UUID> managed = accessPolicy.managedUnitIds();
      reachable = accepting.stream().filter(managed::contains).toList();
      if (reachable.isEmpty()) {
        return false;
      }
    }
    return orgUnitRepository.findAllById(reachable).stream().anyMatch(OrgUnit::isActive);
  }
}
