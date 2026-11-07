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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import de.greluc.krt.profit.basetool.frontend.kernel.model.LocalisedNames;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * DTO for a single entry in the material collection overview of a job order. {@code quantity} is
 * the entry's total physical stock (backs the full-row transfer); {@code allocatedQuantity} is the
 * share earmarked to this job order (Variante C / REQ-INV-027), {@code <= quantity}.
 *
 * @param inventoryEntryId the Lager row
 * @param version the row's version
 * @param ownerName the owner's display name, or {@code null} when redacted
 * @param ownerId the owner, or {@code null} when redacted
 * @param location the storage location's name, or {@code null} when redacted
 * @param locationId the storage location, or {@code null} when redacted
 * @param materialName the material's name
 * @param quality the row's quality
 * @param quantity the row's total stock
 * @param allocatedQuantity the order's share of the row
 * @param delivered whether the order's share is marked delivered
 * @param materialNameDe the material's German name, or {@code null} (REQ-P4K-023)
 */
@DtoMirror
public record MaterialCollectionEntryDto(
    UUID inventoryEntryId,
    long version,
    String ownerName,
    UUID ownerId,
    String location,
    UUID locationId,
    String materialName,
    double quality,
    double quantity,
    double allocatedQuantity,
    boolean delivered,
    @Nullable String materialNameDe) {

  /**
   * Builds an entry without a German material name.
   *
   * @param inventoryEntryId the Lager row
   * @param version the row's version
   * @param ownerName the owner's display name, or {@code null} when redacted
   * @param ownerId the owner, or {@code null} when redacted
   * @param location the storage location's name, or {@code null} when redacted
   * @param locationId the storage location, or {@code null} when redacted
   * @param materialName the material's name
   * @param quality the row's quality
   * @param quantity the row's total stock
   * @param allocatedQuantity the order's share of the row
   * @param delivered whether the order's share is marked delivered
   */
  public MaterialCollectionEntryDto(
      UUID inventoryEntryId,
      long version,
      String ownerName,
      UUID ownerId,
      String location,
      UUID locationId,
      String materialName,
      double quality,
      double quantity,
      double allocatedQuantity,
      boolean delivered) {
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
        null);
  }

  /**
   * Returns the material name the page shows (REQ-P4K-023).
   *
   * @param locale the page locale
   * @return the German name on a German page when there is one, else the name
   */
  public String materialDisplayName(@Nullable Locale locale) {
    return LocalisedNames.display(materialName, materialNameDe, locale);
  }
}
