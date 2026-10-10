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

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AllocationReductionDto;
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

  /**
   * Refuses a book-in the caller may not make, the one book-in rule of the Lager (APPSEC-01): no
   * named owner passes, or needs Logistician+ under {@code rule}; the caller's own Lager passes;
   * another member's needs {@link BookInPolicy#mayBookInFor}, and a personal book-in for another
   * member is refused when {@code rule} says so.
   *
   * @param ownerUserId the member who would own the stock, or {@code null} when none is named
   * @param callerId the caller, or {@code null} when unresolved
   * @param callerIsLogistician whether the caller holds Logistician+
   * @param personal whether the book-in is personal
   * @param rule the path's rule
   * @throws org.springframework.security.access.AccessDeniedException when the book-in is refused
   */
  void requireBookIn(
      @Nullable UUID ownerUserId,
      @Nullable UUID callerId,
      boolean callerIsLogistician,
      boolean personal,
      @NotNull BookInRule rule);

  /**
   * Takes an amount from a row earmarked to a job order: a row left with at most a rounding rest is
   * deleted after its offers are told, any other is lowered with its order slice and the mission
   * slices the reductions name.
   *
   * @param row the locked row
   * @param jobOrderId the order the amount is taken for
   * @param amount the amount
   * @param missionReductions the mission slices to reduce, or {@code null} for the default plan
   * @param reason the stock change
   * @return whether the row was depleted and deleted
   */
  boolean takeFromEarmarkedRow(
      @NotNull InventoryItem row,
      @NotNull UUID jobOrderId,
      double amount,
      @Nullable List<AllocationReductionDto> missionReductions,
      @NotNull StockChangeReason reason);

  /**
   * Consumes a job order's earmarked game-item stock, oldest row first, each row at most by the
   * order's slice; a depleted row is deleted. A shortfall is left unconsumed (REQ-ORDERS-030).
   *
   * @param jobOrderId the order whose earmark is drawn down
   * @param gameItemId the game item
   * @param amount the whole units to consume
   * @param reason the stock change
   * @return one entry per row drawn from
   */
  @NotNull
  List<StockConsumption> consumeEarmarkedItems(
      @NotNull UUID jobOrderId,
      @NotNull UUID gameItemId,
      double amount,
      @NotNull StockChangeReason reason);

  /**
   * Tells the Lager's observers that a row was lowered to {@code remaining}.
   *
   * @param rowId the row
   * @param remaining its new stock
   * @param reason the stock change
   */
  void lowered(@NotNull UUID rowId, double remaining, @NotNull StockChangeReason reason);

  /**
   * Releases every earmark of a job order.
   *
   * @param jobOrderId the order
   */
  void releaseJobOrderEarmarks(@NotNull UUID jobOrderId);

  /**
   * Releases a job order's earmarks on the rows of one material.
   *
   * @param jobOrderId the order
   * @param materialId the material
   */
  void releaseJobOrderMaterialEarmarks(@NotNull UUID jobOrderId, @NotNull UUID materialId);

  /**
   * Releases a job order's earmarks on the rows of one game item.
   *
   * @param jobOrderId the order
   * @param gameItemId the game item
   */
  void releaseJobOrderGameItemEarmarks(@NotNull UUID jobOrderId, @NotNull UUID gameItemId);

  /**
   * Books produced units in as one fresh game-item row, earmarked to the producing order unless
   * {@code earmark} is off, merges it like a Lager book-in and records {@code
   * INVENTORY_RECEIVED_FROM_PRODUCTION} (REQ-INV-032).
   *
   * @param orderId the producing order
   * @param orderDisplayId the order's running number, for the audit trail
   * @param gameItem the produced game item
   * @param amount the produced whole units
   * @param owner the owner
   * @param owningOrgUnit the owning org unit, or {@code null}
   * @param location the location
   * @param personal whether the row is personal
   * @param earmark whether the row is earmarked to the order
   */
  void bookInFromProduction(
      @NotNull UUID orderId,
      @Nullable Integer orderDisplayId,
      @NotNull GameItem gameItem,
      int amount,
      @NotNull User owner,
      @Nullable OrgUnit owningOrgUnit,
      @NotNull Location location,
      boolean personal,
      boolean earmark);

  /**
   * Books refined output in as one fresh material row, earmarked to a job order and, unless
   * personal, to the refinery order's mission, and records {@code
   * INVENTORY_RECEIVED_FROM_REFINERY}.
   *
   * @param refineryOrderId the refinery order, for the audit trail
   * @param assignee the owner
   * @param owningOrgUnit the owning org unit, or {@code null}
   * @param material the material
   * @param location the location
   * @param quality the quality
   * @param amount the amount
   * @param note the trimmed note, or {@code null}
   * @param personal whether the row is personal
   * @param jobOrderId the job order to earmark to, or {@code null}
   * @param jobOrderDisplayId that order's running number, for the audit trail
   * @param missionId the refinery order's mission, or {@code null}
   */
  void bookInFromRefinery(
      @NotNull UUID refineryOrderId,
      @NotNull User assignee,
      @Nullable OrgUnit owningOrgUnit,
      @NotNull Material material,
      @NotNull Location location,
      @Nullable Integer quality,
      @Nullable Double amount,
      @Nullable String note,
      boolean personal,
      @Nullable UUID jobOrderId,
      @Nullable Integer jobOrderDisplayId,
      @Nullable UUID missionId);
}
