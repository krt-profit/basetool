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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One part of a member's exchange stock lot: the member's personal rows of the lot with their
 * shared rows outside any unit, or their own shared rows of it in one org-unit pool, summed
 * (REQ-XCH-016).
 */
public interface ExchangeStockSplitRow {

  /**
   * The lot's key, as the change feed records it.
   *
   * @return the key
   */
  String getLotKey();

  /**
   * The pool's org unit, or {@code null} for the personal part.
   *
   * @return the unit id
   */
  @Nullable
  UUID getOrgUnitId();

  /**
   * The summed amount of the part.
   *
   * @return the amount
   */
  double getAmount();
}
