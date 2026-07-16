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
 * Catalog discriminator of the {@code /api/v1/inventory} read family (REQ-INV-029, ADR-0100). Since
 * V220 an {@code InventoryItem} row stocks either a material or a game item (XOR); the read
 * endpoints select which population they serve via a {@code catalog} query parameter that defaults
 * to {@link #MATERIAL}, so pre-item clients keep receiving exactly the rows they always did.
 */
public enum InventoryCatalog {

  /**
   * Material stock rows ({@code material_id} set): the pre-V220 contract with the quality
   * dimension, mission allocations and {@code minQuality} filtering.
   */
  MATERIAL,

  /**
   * Game-item stock rows ({@code game_item_id} set): no quality dimension, whole-unit amounts, and
   * job-order allocations only — {@code minQuality} / {@code missionIds} parameters are rejected
   * with 400 for this catalog.
   */
  ITEM
}
