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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link InventoryJobOrderAllocation} — the per-entry job-order quantity
 * slices of the Variante-C split (REQ-INV-027). Fulfilment sums that used to read the scalar {@code
 * inventory_item.job_order_id} traverse the allocation collection from {@link
 * InventoryItemRepository}; direct allocation reads/deletes that the entry-collection cascade
 * cannot express (e.g. the material-scoped handover unlink) live here.
 */
@Repository
public interface InventoryJobOrderAllocationRepository
    extends JpaRepository<InventoryJobOrderAllocation, UUID> {}
