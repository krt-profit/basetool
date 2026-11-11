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

import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The Lager row locks another module takes before it reads rows for a write of its own, so the
 * write and a Lager delete of the rows take turns (ADR-0362). The locks write nothing; every lock
 * joins the caller's transaction ({@code MANDATORY}) and holds until it ends.
 */
public interface StockRowLocks {

  /**
   * Locks one row the transaction has not read yet, in a single statement, before a Materialbörse
   * offer write on it reads it; its associations load on first access.
   *
   * @param rowId the row
   * @return the row under a pessimistic write lock, or empty when unknown or deleted by another
   *     transaction while the lock waited
   */
  @NotNull
  Optional<StockRow> lockRowForOfferWrite(@NotNull UUID rowId);

  /**
   * Locks every row of a member, personal and shared, in ascending id order, before the account
   * deletion reads the Materialbörse offers standing on them.
   *
   * @param member the member
   */
  void lockRowsOf(@NotNull UUID member);
}
