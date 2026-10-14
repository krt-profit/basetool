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

import de.greluc.krt.profit.basetool.backend.inventory.api.DonationEarmark;
import de.greluc.krt.profit.basetool.backend.inventory.api.EarmarkTargets;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells the Lager which pledge an earmarked slice of a row belongs to (REQ-INV-058), so a row can
 * show to whom its stock is pledged without the inventory module naming this one.
 */
@Component
@RequiredArgsConstructor
public class SupplyDonationEarmarks implements EarmarkTargets.Donations {

  private final SupplyDonationRepository donationRepository;
  private final OrgUnitRepository orgUnitRepository;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Map<UUID, DonationEarmark> donations(@NotNull Collection<UUID> ids) {
    Map<UUID, DonationEarmark> byId = new HashMap<>();
    if (ids.isEmpty()) {
      return byId;
    }
    List<SupplyDonation> donations = donationRepository.findAllById(ids);
    Map<UUID, String> unitNames =
        orgUnitRepository
            .findAllById(
                donations.stream()
                    .map(SupplyDonation::getReceivingOrgUnitId)
                    .collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(OrgUnit::getId, OrgUnit::getName, (a, _) -> a));
    for (SupplyDonation donation : donations) {
      byId.put(
          donation.getId(),
          new DonationEarmark(donation.getId(), unitNames.get(donation.getReceivingOrgUnitId())));
    }
    return byId;
  }
}
