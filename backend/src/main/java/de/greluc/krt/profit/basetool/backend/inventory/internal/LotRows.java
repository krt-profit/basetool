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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeEffects;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.util.List;
import lombok.With;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Splits a part off a Lager row and lowers or deletes the source, the one place that copies a lot's
 * identity and keeps the earmarks, the pledged donations and the Materialbörse in step with a
 * lowered row (REQ-INV-007, REQ-MARKET-013).
 *
 * <p>Neither method opens a transaction or adds a write of its own beyond the source's flush or
 * delete; both run inside the caller's transaction and locks, and the caller saves the split-off
 * row where its flow needs it.
 */
final class LotRows {

  /** The rest below which a lowered row counts as depleted and is deleted. */
  static final double DEPLETED_BELOW = 1e-4;

  private LotRows() {}

  /**
   * Where a split-off part goes: its holder, location, pool and markers.
   *
   * @param user the holder
   * @param location the location
   * @param owningOrgUnit the pool's org unit, or {@code null}
   * @param personal the personal marker
   * @param stolen the stolen marker
   */
  @With
  record Placement(
      User user,
      Location location,
      @Nullable OrgUnit owningOrgUnit,
      boolean personal,
      boolean stolen) {

    /**
     * Returns the placement of a row itself, the base a caller overrides.
     *
     * @param row the source row
     * @return the row's holder, location, pool and markers
     */
    static @NotNull Placement of(@NotNull InventoryItem row) {
      return new Placement(
          row.getUser(),
          row.getLocation(),
          row.getOwningOrgUnit(),
          Boolean.TRUE.equals(row.getPersonal()),
          Boolean.TRUE.equals(row.getStolen()));
    }
  }

  /**
   * The source row after it was lowered.
   *
   * @param row the flushed source, or the deleted one when it was depleted
   * @param depleted whether the source was depleted and deleted
   * @param offers the Materialbörse offers the change lowered or will remove
   */
  record Lowered(
      @NotNull InventoryItem row, boolean depleted, @NotNull StockChangeEffects offers) {}

  /**
   * Builds the unsaved row of a part split off a source: the source's material or game item and
   * quality, the amount rounded to SCU scale, and the given placement; no note and no earmark.
   *
   * @param source the source row
   * @param amount the part
   * @param placement where the part goes
   * @return the new, unsaved row
   */
  static @NotNull InventoryItem split(
      @NotNull InventoryItem source, double amount, @NotNull Placement placement) {
    InventoryItem part = new InventoryItem();
    part.setUser(placement.user());
    part.setOwningOrgUnit(placement.owningOrgUnit());
    part.setMaterial(source.getMaterial());
    part.setGameItem(source.getGameItem());
    part.setLocation(placement.location());
    part.setQuality(source.getQuality());
    part.setAmount(ScuAmounts.roundToScuScale(amount));
    part.setPersonal(placement.personal());
    part.setStolen(placement.stolen());
    return part;
  }

  /**
   * Lowers a managed row to its rest, or deletes it when the rest is depleted, and tells the
   * Materialbörse.
   *
   * @param repository the Lager repository
   * @param observer the observer of lowered and deleted rows
   * @param row the managed source row, its earmarks already reduced by the caller
   * @param remaining the rest, already rounded
   * @param reason the stock change
   * @return the flushed row or none, and the offer effects
   * @throws OverAllocationException when a depleted row still carries a pledged donation, or the
   *     rest no longer carries the row's earmarks
   */
  static @NotNull Lowered lower(
      @NotNull InventoryItemRepository repository,
      @NotNull StockChangeObserver observer,
      @NotNull InventoryItem row,
      double remaining,
      @NotNull StockChangeReason reason) {
    if (remaining <= DEPLETED_BELOW) {
      InventoryDonationGuards.requireNoDonation(row);
      int removed = observer.beforeDelete(List.of(row.getId()), reason);
      repository.delete(row);
      return new Lowered(row, true, new StockChangeEffects(0, removed));
    }
    row.setAmount(remaining);
    if (!InventoryAllocations.fits(row)) {
      throw new OverAllocationException();
    }
    InventoryItem saved = repository.saveAndFlush(row);
    int reduced = observer.lower(row.getId(), remaining, reason);
    return new Lowered(saved, false, new StockChangeEffects(reduced, 0));
  }
}
