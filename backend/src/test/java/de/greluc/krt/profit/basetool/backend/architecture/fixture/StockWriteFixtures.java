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

package de.greluc.krt.profit.basetool.backend.architecture.fixture;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import java.util.UUID;

/**
 * Planted Lager writes outside the inventory module, so the stock-write ownership rule is proven
 * able to fail. None of these types is a Spring bean.
 */
public final class StockWriteFixtures {

  /** Not instantiable. */
  private StockWriteFixtures() {}

  /** A foreign service that writes Lager rows itself. */
  public static final class PlantedStockWriter {

    private final InventoryItemRepository repository;

    /**
     * Creates the fixture.
     *
     * @param repository the Lager repository the planted methods write through
     */
    public PlantedStockWriter(InventoryItemRepository repository) {
      this.repository = repository;
    }

    /**
     * Saves a Lager row.
     *
     * @param item the row
     */
    public void saves(InventoryItem item) {
      repository.save(item);
    }

    /**
     * Sets a Lager row's amount.
     *
     * @param item the row
     */
    public void setsTheAmount(InventoryItem item) {
      item.setAmount(1.0);
    }

    /**
     * Earmarks a Lager row.
     *
     * @param item the row
     * @param order the order
     */
    public void earmarks(InventoryItem item, JobOrder order) {
      InventoryAllocations.addJobOrder(item, order, 1.0, false);
    }

    /**
     * Releases an order's earmarks with a bulk statement.
     *
     * @param jobOrderId the order
     */
    public void releasesInBulk(UUID jobOrderId) {
      repository.deleteJobOrderAllocationsByJobOrder(jobOrderId);
    }

    /**
     * Reads a Lager row, which the rule allows.
     *
     * @param id the row
     */
    public void reads(UUID id) {
      repository.findById(id);
    }
  }
}
