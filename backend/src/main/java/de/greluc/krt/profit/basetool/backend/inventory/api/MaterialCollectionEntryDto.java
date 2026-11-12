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

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One inventory entry in the material collection overview of a job order.
 *
 * <p>{@code quantity} is the entry's total physical stock; {@code allocatedQuantity} is the share
 * earmarked to this order (REQ-INV-027) and is at most {@code quantity}.
 *
 * @param inventoryEntryId the Lager row
 * @param version the row's version
 * @param ownerName the owner's display name, or {@code null} when redacted
 * @param ownerId the owner, or {@code null} when redacted
 * @param location the storage location's name, or {@code null} when redacted
 * @param locationId the storage location, or {@code null} when redacted
 * @param materialName the material's name, or {@code null}
 * @param quality the row's quality, or {@code null}
 * @param quantity the row's total stock
 * @param allocatedQuantity the order's share of the row
 * @param delivered whether the order's share is marked delivered
 * @param materialNameDe the material's German name, or {@code null} (REQ-P4K-023)
 * @param canEdit whether the current caller has Inventory write authority for this row
 */
public record MaterialCollectionEntryDto(
    UUID inventoryEntryId,
    long version,
    @Nullable String ownerName,
    @Nullable UUID ownerId,
    @Nullable String location,
    @Nullable UUID locationId,
    @Nullable String materialName,
    @Nullable Double quality,
    Double quantity,
    Double allocatedQuantity,
    boolean delivered,
    @Nullable String materialNameDe,
    boolean canEdit) {

  /**
   * Builds an entry without a German material name.
   *
   * @param inventoryEntryId the Lager row
   * @param version the row's version
   * @param ownerName the owner's display name, or {@code null} when redacted
   * @param ownerId the owner, or {@code null} when redacted
   * @param location the storage location's name, or {@code null} when redacted
   * @param locationId the storage location, or {@code null} when redacted
   * @param materialName the material's name, or {@code null}
   * @param quality the row's quality, or {@code null}
   * @param quantity the row's total stock
   * @param allocatedQuantity the order's share of the row
   * @param delivered whether the order's share is marked delivered
   * @param canEdit whether the current caller has Inventory write authority for this row
   */
  public MaterialCollectionEntryDto(
      UUID inventoryEntryId,
      long version,
      @Nullable String ownerName,
      @Nullable UUID ownerId,
      @Nullable String location,
      @Nullable UUID locationId,
      @Nullable String materialName,
      @Nullable Double quality,
      Double quantity,
      Double allocatedQuantity,
      boolean delivered,
      boolean canEdit) {
    this(
        inventoryEntryId,
        version,
        ownerName,
        ownerId,
        location,
        locationId,
        materialName,
        quality,
        quantity,
        allocatedQuantity,
        delivered,
        null,
        canEdit);
  }
}
