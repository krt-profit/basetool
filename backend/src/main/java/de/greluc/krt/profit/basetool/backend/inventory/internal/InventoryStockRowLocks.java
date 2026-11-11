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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRowLocks;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The Lager's answer to {@link StockRowLocks}: locking reads on {@code inventory_item}. */
@Component
@RequiredArgsConstructor
public class InventoryStockRowLocks implements StockRowLocks {

  private final InventoryItemRepository inventoryRepository;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Optional<StockRow> lockRowForOfferWrite(@NotNull UUID rowId) {
    return inventoryRepository.lockById(rowId).map(StockRow.class::cast);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockRowsOf(@NotNull UUID member) {
    inventoryRepository.lockIdsOfUser(member);
  }
}
