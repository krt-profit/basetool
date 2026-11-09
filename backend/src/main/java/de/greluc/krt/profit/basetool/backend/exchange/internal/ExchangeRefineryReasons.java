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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

/**
 * The reasons only a refinery-order op is not applied with, as the change result names them
 * (REQ-XCH-052, REQ-XCH-053, REQ-XCH-055); the reasons it shares with other resources are {@link
 * ExchangeReasons}.
 */
final class ExchangeRefineryReasons {

  /** The refusal of an order the member does not own or the client's key does not name. */
  static final String ORDER_UNKNOWN = "ORDER_UNKNOWN";

  /** The refusal of a good the order does not hold. */
  static final String GOOD_UNKNOWN = "GOOD_UNKNOWN";

  /** The refusal of a create without goods, or of a good-remove of the order's last good. */
  static final String GOODS_REQUIRED = "GOODS_REQUIRED";

  /** The refusal of a create whose key already names an order. */
  static final String ALREADY_EXISTS = "ALREADY_EXISTS";

  /** The refusal of an op on an order an earlier op of the set was refused on. */
  static final String PRECEDING_OP_REJECTED = "PRECEDING_OP_REJECTED";

  /**
   * The refusal of a status change or a delete of a stored order, and of store lines on an order
   * that is completed or stored.
   */
  static final String ORDER_STORED = "ORDER_STORED";

  /** The refusal of a pickup of a cancelled order. */
  static final String ORDER_CANCELED = "ORDER_CANCELED";

  /** The refusal of a location the web's refinery picker does not offer. */
  static final String NOT_A_REFINERY = "NOT_A_REFINERY";

  /** The refusal of an input material that is neither raw nor manual-raw. */
  static final String MATERIAL_NOT_RAW = "MATERIAL_NOT_RAW";

  /** The refusal of a receiver the member may not book onto, or that names nobody. */
  static final String RECEIVER_UNKNOWN = "RECEIVER_UNKNOWN";

  /** The refusal of a job order that is unknown, inactive, invisible or does not need the line. */
  static final String JOB_ORDER_INVALID = "JOB_ORDER_INVALID";

  /** The refusal of a store line whose material is none of the order's outputs. */
  static final String MATERIAL_NOT_AN_OUTPUT = "MATERIAL_NOT_AN_OUTPUT";

  private ExchangeRefineryReasons() {}
}
