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

package de.greluc.krt.profit.basetool.backend.joborder.api;

/** The exclusive fulfillment kind of a {@link JobOrder}. */
public enum JobOrderType {

  /** Order requesting raw materials directly (commodity + quantity + minimum quality). */
  MATERIAL,

  /** Order requesting finished items; needed materials are derived from each item's blueprint. */
  ITEM,

  /** Order requesting a net amount of aUEC. */
  AUEC;

  /**
   * Identifies kinds that can accept physical material or item allocations.
   *
   * @return whether this order kind participates in stock fulfillment
   */
  public boolean usesStock() {
    return switch (this) {
      case MATERIAL, ITEM -> true;
      case AUEC -> false;
    };
  }
}
