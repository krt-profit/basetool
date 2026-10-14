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

import de.greluc.krt.profit.basetool.backend.supply.api.NeedRef;
import de.greluc.krt.profit.basetool.backend.supply.api.SupplyNeeds;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The needs as the purchasing module reads and credits them (plan §5.3): plain reads that reveal
 * nothing the caller's own gate has not already allowed, and the credit of an accepted purchase
 * delivery.
 */
@Service
@RequiredArgsConstructor
public class SupplyNeedQueries implements SupplyNeeds {

  private final SupplyNeedRepository needRepository;
  private final SupplyNeedService needService;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Optional<NeedRef> find(@NotNull UUID needId) {
    return needRepository.findWithCatalogueById(needId).map(SupplyMapper::toRef);
  }

  @Override
  @Transactional(readOnly = true)
  public @NotNull Map<UUID, NeedRef> findAll(@NotNull Collection<UUID> needIds) {
    Map<UUID, NeedRef> byId = new HashMap<>();
    if (needIds.isEmpty()) {
      return byId;
    }
    for (SupplyNeed need : needRepository.findWithCatalogueByIdIn(needIds)) {
      byId.put(need.getId(), SupplyMapper.toRef(need));
    }
    return byId;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void creditPurchase(@NotNull UUID needId, double amount) {
    needService.creditPurchase(needId, amount);
  }
}
