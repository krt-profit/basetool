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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

/** Localized refusals shared by physical order fulfillment paths. */
public final class JobOrderStockErrors {
  /** The named physical source has no allocation to the affected order. */
  static final String ITEM_NOT_LINKED_TO_ORDER = "error.job_order.inventory_item_not_linked";

  /** The physical quality cannot satisfy the chosen requirement. */
  static final String QUALITY_BELOW_FLOOR = "error.job_order.quality_below_floor";

  /** The explicitly named requirement tier does not occur on the order. */
  static final String QUALITY_TIER_NOT_ON_ORDER = "error.job_order.quality_tier_not_on_order";

  /** Material can only be handed over against a material order. */
  static final String MATERIAL_ORDER_REQUIRED = "error.job_order.handover.material_order_required";

  /** The selected stock entry does not contain a material. */
  static final String MATERIAL_ROW_REQUIRED = "error.job_order.handover.material_row_required";

  /** The handover amount must be positive. */
  static final String POSITIVE_AMOUNT_REQUIRED =
      "error.job_order.handover.positive_amount_required";

  /** The handover amount exceeds available stock. */
  static final String INSUFFICIENT_STOCK = "error.job_order.handover.insufficient_stock";

  /** The handover amount exceeds the quantity allocated to this order. */
  static final String INSUFFICIENT_ALLOCATION = "error.job_order.handover.insufficient_allocation";

  /** Piece-counted material requires a whole handover amount. */
  static final String WHOLE_PIECE_REQUIRED = "error.job_order.handover.whole_piece_required";

  /** Finished items can only be handed over against an item order. */
  static final String ITEM_ORDER_REQUIRED = "error.job_order.handover.item_order_required";

  /** A selected item line does not belong to this order. */
  static final String FOREIGN_ITEM_LINE = "error.job_order.handover.foreign_item_line";

  /** The handover exceeds the manufactured quantity still available for delivery. */
  static final String INSUFFICIENT_MANUFACTURED_ITEMS =
      "error.job_order.handover.insufficient_manufactured_items";

  private JobOrderStockErrors() {}
}
