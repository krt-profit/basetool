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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Why a Lager row was lowered or deleted, as the offer ratchet records it in an audit row's {@code
 * reason} detail (REQ-MARKET-013).
 */
@Getter
@RequiredArgsConstructor
public enum StockChangeReason {
  /** A single book-out. */
  CHECKOUT("checkout"),
  /** A bulk book-out. */
  BULK_CHECKOUT("bulk-checkout"),
  /** A transfer to another holder. */
  TRANSFER("transfer"),
  /** A rebooking into another row. */
  REBOOK("rebook"),
  /** A wipe of the shared stock. */
  WIPE("wipe"),
  /** A job-order handover. */
  HANDOVER("handover"),
  /** A job-order production consuming stock. */
  PRODUCTION("production"),
  /** An exchange stock change. */
  STOCK("stock"),
  /** The erasure of the owning account. */
  USER_DELETION("user-deletion");

  /** The persisted code of the reason. */
  private final String code;
}
