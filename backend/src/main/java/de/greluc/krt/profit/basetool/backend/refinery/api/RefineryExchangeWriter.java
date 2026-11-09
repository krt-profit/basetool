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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Writes a member's own refinery orders for an exchange client and for the undo of its writes, with
 * the web's rules, audit and notices (REQ-XCH-052, REQ-XCH-056). Every method runs in the caller's
 * transaction and touches only orders the member owns; a method that writes an existing order
 * expects the caller to have locked it with {@code lockOwn} first.
 */
public interface RefineryExchangeWriter {

  /**
   * Locks one of the member's orders for the rest of the transaction; an order of another member is
   * not locked.
   *
   * @param owner the member
   * @param orderId the order
   * @return the order, or empty when it does not exist or another member owns it
   */
  @NotNull
  Optional<RefineryExchangeOrder> lockOwn(@NotNull UUID owner, @NotNull UUID orderId);

  /**
   * Locks the member's orders among the given ids for the rest of the transaction, in ascending id
   * order; an order of another member is not locked.
   *
   * @param owner the member
   * @param orderIds the orders
   * @return the member's orders among them, ordered by id
   */
  @NotNull
  @Unmodifiable
  List<RefineryExchangeOrder> lockOwn(@NotNull UUID owner, @NotNull Collection<UUID> orderIds);

  /**
   * Creates an order owned by the member and marked as created by the client.
   *
   * @param owner the member
   * @param clientId the client
   * @param orgUnitId the owning unit, one of the member's direct memberships
   * @param edit the order's location, status and every further part it starts with; the location
   *     and the status are required, and every good is a new row
   * @return the saved order, counters at 0, its goods in the order the edit names them
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the location is
   *     not an offered refinery, a material may not be refined or the unit is no membership
   */
  @NotNull
  RefineryExchangeOrder create(
      @NotNull UUID owner,
      @NotNull String clientId,
      @NotNull UUID orgUnitId,
      @NotNull RefineryExchangeEdit edit);

  /**
   * Writes the parts an edit carries to one of the member's orders, raising each written section's
   * counter by one; the caller has checked the counters on the locked order. A client's write never
   * changes a stored order's status; the member's undo may set back the status a client's cancel
   * changed.
   *
   * @param owner the member
   * @param clientId the client that writes, or {@code null} for the member's undo
   * @param orderId the order
   * @param edit the parts to write
   * @return the saved order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the member owns no
   *     such order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the location is
   *     not a refinery, a material may not be refined, or a client would change the status of a
   *     stored order
   */
  @NotNull
  RefineryExchangeOrder edit(
      @NotNull UUID owner,
      @Nullable String clientId,
      @NotNull UUID orderId,
      @NotNull RefineryExchangeEdit edit);

  /**
   * Cancels one of the member's orders as the web's cancel does, stored or not, raising the
   * game-data counter by one, audited {@code REFINERY_ORDER_CANCELED} (REQ-XCH-055).
   *
   * @param owner the member
   * @param clientId the client that cancels
   * @param orderId the order, not yet cancelled
   * @return the saved order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the member owns no
   *     such order
   */
  @NotNull
  RefineryExchangeOrder cancel(
      @NotNull UUID owner, @NotNull String clientId, @NotNull UUID orderId);

  /**
   * Closes one of the member's open or running orders as collected in the game: status {@code
   * COMPLETED} with the client's pickup marker and the game-data counter raised by one
   * (REQ-XCH-053, REQ-REFINERY-028). Without lines nothing is booked; with lines the order is
   * stored as the web's store stores it, {@code storedAt} set and audited {@code
   * REFINERY_ORDER_STORED} (REQ-REFINERY-022). The caller has checked the status, the counter and
   * every line on the locked order.
   *
   * @param owner the member
   * @param clientId the client that reports the pickup
   * @param orderId the order
   * @param lines the store lines, each of an output material of the order; empty to book nothing
   * @return the saved order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the member owns no
   *     such order or a line names an entry that does not exist
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the order is
   *     neither {@code OPEN} nor {@code IN_PROGRESS}, or a line's material is no output of it
   */
  @NotNull
  RefineryExchangeOrder pickUp(
      @NotNull UUID owner,
      @NotNull String clientId,
      @NotNull UUID orderId,
      @NotNull List<RefineryExchangeStoreLine> lines);

  /**
   * Removes a client's pickup marker from one of the member's orders, for the undo of a pickup; the
   * status and the counters are left to the undo's own edit (REQ-XCH-056).
   *
   * @param owner the member
   * @param orderId the order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the member owns no
   *     such order
   */
  void clearPickup(@NotNull UUID owner, @NotNull UUID orderId);

  /**
   * Removes one of the member's orders that is not stored, with its goods, audited {@code
   * REFINERY_ORDER_DELETED}; booked stock is left as it is (REQ-XCH-055).
   *
   * @param owner the member
   * @param orderId the order
   * @return what the order held beyond the fields a client writes, for its undo
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the member owns no
   *     such order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when it is stored
   */
  @NotNull
  RefineryExchangeOrigin delete(@NotNull UUID owner, @NotNull UUID orderId);

  /**
   * Recreates a removed order of the member under its former id, its goods under their former row
   * ids, audited {@code REFINERY_ORDER_RESTORED} (REQ-XCH-056). The last change is the member's.
   *
   * @param owner the member
   * @param restore the order as it comes back
   * @return the recreated order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the location, the
   *     method or a material is gone
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when a material may
   *     not be refined or the unit is no direct membership of the member
   */
  @NotNull
  RefineryExchangeOrder restore(@NotNull UUID owner, @NotNull RefineryExchangeRestore restore);
}
