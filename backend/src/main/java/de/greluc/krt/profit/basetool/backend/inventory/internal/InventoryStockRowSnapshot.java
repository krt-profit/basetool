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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup.StockOfferFact;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Holds immutable physical and allocation facts independently of a managed stock entity. */
@Getter
@EqualsAndHashCode
@RequiredArgsConstructor
final class InventoryStockRowSnapshot implements StockRow {
  /** The physical row identity. */
  private final @NotNull UUID id;

  /** The observed persisted version. */
  private final @NotNull Long version;

  /** The holder reference, without a cached write grant. */
  @EqualsAndHashCode.Exclude private final @NotNull User user;

  /** The owning pool reference, or null for unassigned stock. */
  @EqualsAndHashCode.Exclude private final @Nullable OrgUnit owningOrgUnit;

  /** The material reference, or null for game-item stock. */
  @EqualsAndHashCode.Exclude private final @Nullable Material material;

  /** The game-item reference, or null for material stock. */
  @EqualsAndHashCode.Exclude private final @Nullable GameItem gameItem;

  /** The observed physical location. */
  @EqualsAndHashCode.Exclude private final @NotNull Location location;

  /** The material quality, or null for game-item stock. */
  private final @Nullable Integer quality;

  /** The observed physical quantity. */
  private final @NotNull Double amount;

  /** The observed stolen marker. */
  private final @NotNull Boolean stolen;

  /** The observed personal marker. */
  private final @NotNull Boolean personal;

  /** The immutable job-order slices of the observed row. */
  @Getter(AccessLevel.PACKAGE)
  private final @NotNull Map<UUID, Double> jobOrders;

  /** The total reserved for missions. */
  @Getter(AccessLevel.PACKAGE)
  private final double missionAmount;

  /** Whether any mission slice exists, including a zero-amount slice. */
  @Getter(AccessLevel.PACKAGE)
  private final boolean missionPresent;

  /** The total pledged to donations. */
  @Getter(AccessLevel.PACKAGE)
  private final double donationAmount;

  /** The scalar creation instant used by owning source ordering. */
  @Getter(AccessLevel.PACKAGE)
  private final @Nullable Instant createdAt;

  /** The observed offer presence and active quantity. */
  @Getter(AccessLevel.PACKAGE)
  private final @NotNull StockOfferFact offer;

  /** Returns the persistent holder identity independently of its proxy representation. */
  @EqualsAndHashCode.Include
  private UUID holderIdentity() {
    return user.getId();
  }

  /** Returns the persistent pool identity independently of its discriminator proxy. */
  @EqualsAndHashCode.Include
  private @Nullable UUID poolIdentity() {
    return owningOrgUnit == null ? null : owningOrgUnit.getId();
  }

  /** Returns the material identity used by physical snapshot equality. */
  @EqualsAndHashCode.Include
  private @Nullable UUID materialIdentity() {
    return material == null ? null : material.getId();
  }

  /** Returns the game-item identity used by physical snapshot equality. */
  @EqualsAndHashCode.Include
  private @Nullable UUID gameItemIdentity() {
    return gameItem == null ? null : gameItem.getId();
  }

  /** Returns the persistent location identity independently of its proxy representation. */
  @EqualsAndHashCode.Include
  private UUID locationIdentity() {
    return location.getId();
  }

  /**
   * Returns the captured total of the row's job-order slices.
   *
   * @return the reserved job-order amount
   */
  @Override
  public double jobOrderReserved() {
    return jobOrders.values().stream().mapToDouble(Double::doubleValue).sum();
  }

  /**
   * Returns the captured mission reservation.
   *
   * @return the reserved mission amount
   */
  @Override
  public double missionReserved() {
    return missionAmount;
  }

  /**
   * Returns the captured donation reservation.
   *
   * @return the pledged donation amount
   */
  @Override
  public double donationReserved() {
    return donationAmount;
  }

  /**
   * Reports whether the captured row carried a slice for the selected order.
   *
   * @param jobOrderId the selected order
   * @return whether the captured slice exists
   */
  @Override
  public boolean isEarmarkedForJobOrder(@NotNull UUID jobOrderId) {
    return jobOrders.containsKey(jobOrderId);
  }

  /**
   * Returns the captured amount of the selected order's slice.
   *
   * @param jobOrderId the selected order
   * @return its reserved amount, or zero without a slice
   */
  @Override
  public double jobOrderSliceAmount(@NotNull UUID jobOrderId) {
    return jobOrders.getOrDefault(jobOrderId, 0.0);
  }
}
