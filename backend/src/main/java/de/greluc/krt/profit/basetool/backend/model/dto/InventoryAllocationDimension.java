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

package de.greluc.krt.profit.basetool.backend.model.dto;

/**
 * Selects which of an inventory entry's two independent quantity splits (Variante C, REQ-INV-027)
 * an {@link InventoryAllocationWriteDto} operates on. The two dimensions are validated separately,
 * so every allocation write names exactly one of them.
 */
public enum InventoryAllocationDimension {

  /** The job-order split — the {@code InventoryJobOrderAllocation} rows of the entry. */
  JOB_ORDER,

  /** The mission split — the {@code InventoryMissionAllocation} rows of the entry. */
  MISSION
}
