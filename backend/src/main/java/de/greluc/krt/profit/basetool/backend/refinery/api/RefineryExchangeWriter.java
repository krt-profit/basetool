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

import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Writes a member's own refinery orders for an exchange client and for the undo of its writes, with
 * the web's rules, audit and notices (REQ-XCH-052, REQ-XCH-056). Every method runs in the caller's
 * transaction and touches only orders the member owns.
 */
public interface RefineryExchangeWriter {

  /**
   * Locks one of the member's orders for the rest of the transaction.
   *
   * @param owner the member
   * @param orderId the order
   * @return the order, or empty when it does not exist or another member owns it
   */
  @NotNull
  Optional<RefineryExchangeOrder> lockOwn(@NotNull UUID owner, @NotNull UUID orderId);

  /**
   * Creates an order owned by the member and marked as created by the client.
   *
   * @param owner the member
   * @param clientId the client
   * @param orgUnitId the owning unit, one of the member's direct memberships
   * @param edit the order's location, status and every further part it starts with; the location
   *     and the status are required
   * @return the saved order, counters at 0
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
   * counter by one; the caller has checked the counters on the locked order.
   *
   * @param owner the member
   * @param clientId the client that writes, or {@code null} for the member's undo
   * @param orderId the order
   * @param edit the parts to write
   * @return the saved order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the member owns no
   *     such order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the location is
   *     not a refinery, a material may not be refined, or the status of a stored order would change
   */
  @NotNull
  RefineryExchangeOrder edit(
      @NotNull UUID owner,
      @Nullable String clientId,
      @NotNull UUID orderId,
      @NotNull RefineryExchangeEdit edit);

  /**
   * Closes one of the member's open or running orders as collected in the game: status {@code
   * COMPLETED} with the client's pickup marker, the game-data counter raised by one and nothing
   * booked (REQ-XCH-053, REQ-REFINERY-028); the caller has checked the status and the counter on
   * the locked order.
   *
   * @param owner the member
   * @param clientId the client that reports the pickup
   * @param orderId the order
   * @return the saved order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the member owns no
   *     such order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when the order is
   *     neither {@code OPEN} nor {@code IN_PROGRESS}
   */
  @NotNull
  RefineryExchangeOrder pickUp(
      @NotNull UUID owner, @NotNull String clientId, @NotNull UUID orderId);

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
   * REFINERY_ORDER_DELETED}.
   *
   * @param owner the member
   * @param orderId the order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the member owns no
   *     such order
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException when it is stored
   */
  void delete(@NotNull UUID owner, @NotNull UUID orderId);
}
