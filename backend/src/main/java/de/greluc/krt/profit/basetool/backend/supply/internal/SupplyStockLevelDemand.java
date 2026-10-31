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

import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLevelDemandSource;
import de.greluc.krt.profit.basetool.backend.supply.api.NeedStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports the open amounts of the units' {@code OPEN} supply needs to the minimum stock levels
 * (REQ-SUP-003, REQ-INV-067); a paused need is not asked for and is left out.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplyStockLevelDemand implements StockLevelDemandSource {

  private final SupplyNeedRepository supplyNeedRepository;

  @Override
  public @NotNull List<Request> outstandingRequests(
      @NotNull Collection<UUID> unitIds, @NotNull Collection<UUID> subjectIds) {
    return supplyNeedRepository
        .findByStatusAndUnitsAndSubjects(NeedStatus.OPEN, unitIds, subjectIds)
        .stream()
        .filter(need -> need.openAmount() > 0)
        .map(need -> {
          QualityTier tier = need.getQualityTier();
          UUID subjectId =
              need.getMaterial() != null ? need.getMaterial().getId() : need.getGameItem().getId();
          return new Request(
              need.getOrgUnitId(),
              subjectId,
              tier == null ? 0 : tier.getMinQuality(),
              need.openAmount());
        })
        .toList();
  }
}
