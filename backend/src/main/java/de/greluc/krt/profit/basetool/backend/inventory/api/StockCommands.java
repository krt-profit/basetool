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

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The writes another module may ask of the Lager for a member's lots (plan §5.3, ADR-0229). Every
 * command joins the caller's transaction ({@code MANDATORY}).
 *
 * <p>The lot-lock protocol is the Lager's: a writer takes the advisory lock of every lot it will
 * touch with {@link #lockLots} before it reads any row, then each lot's rows with {@link
 * #lockLotRows} in the order of the lots' keys, and only then books.
 */
public interface StockCommands {

  /**
   * Takes the transaction-scoped advisory lock of each of a member's lots, in ascending order of
   * the lock keys, before any of their rows is read.
   *
   * @param member the member
   * @param lotKeys the lots' keys as the change feed records them
   */
  void lockLots(@NotNull UUID member, @NotNull Collection<String> lotKeys);

  /**
   * Locks the member's rows of a lot, personal and shared, in the order a book-out takes them: the
   * personal rows first, then the rows without an org unit, then the oldest.
   *
   * @param member the member
   * @param lot the lot
   * @return the rows, locked for this transaction
   */
  @NotNull
  List<InventoryItem> lockLotRows(@NotNull UUID member, @NotNull StockLot lot);

  /**
   * Books stock in as a new personal row without an org unit, which piece-counted stock then joins
   * to its existing row (REQ-INV-026), and records {@code INVENTORY_ITEM_CREATED}.
   *
   * @param member the member, owner and actor
   * @param lot the lot
   * @param amount the amount
   */
  void bookIn(@NotNull UUID member, @NotNull StockLot lot, double amount);

  /**
   * Books an amount out of one locked row through the Lager's own book-out, which audits it and
   * every Materialbörse offer it lowers or removes.
   *
   * @param member the member, recorded as the actor
   * @param row the locked row
   * @param amount the amount to take
   * @return the offers the book-out lowered and removed
   */
  @NotNull
  StockChangeEffects bookOutRow(@NotNull UUID member, @NotNull InventoryItem row, double amount);

  /**
   * Sets or clears the stolen marker of one row, the whole row or a part of it, through the Lager's
   * own stolen marking (REQ-INV-053).
   *
   * @param member the member, recorded as the actor
   * @param row the row
   * @param stolen the marker to set
   * @param amount the part to mark, or {@code null} for the whole row
   */
  void markStolen(
      @NotNull UUID member, @NotNull InventoryItem row, boolean stolen, @Nullable Double amount);
}
