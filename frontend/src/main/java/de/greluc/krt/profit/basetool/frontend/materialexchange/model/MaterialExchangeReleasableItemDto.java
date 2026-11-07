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

package de.greluc.krt.profit.basetool.frontend.materialexchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend view of one Materialbörse release-picker entry — a Lager row of the caller's own stock
 * eligible for release. Deserialized from {@code /api/v1/material-exchange/releasable-items}.
 * Carries both a material row ({@link #kind} {@code MATERIAL}) and a game-item row ({@link #kind}
 * {@code ITEM}, a stock-backed item offer, REQ-MARKET-014).
 *
 * @param inventoryItemId the Lager row to release.
 * @param kind which offer kind releasing this row produces ({@code MATERIAL} / {@code ITEM}).
 * @param materialName the display name of the row's catalog entry (material name or game-item
 *     name).
 * @param quantityType the row's quantity unit name ({@code SCU} / {@code PIECE}); the picker JS
 *     renders the amount in this unit instead of always SCU.
 * @param quality the row's quality (0–1000); {@code null} for a game-item row (items have no
 *     quality).
 * @param amount the row's quantity, expressed in the row's {@link #quantityType} unit.
 * @param locationName the row's location (shown only in the owner's own picker).
 * @param alreadyReleased whether an active offer already exists for this row.
 * @param stolen whether the row is marked „gestohlen" (REQ-INV-053).
 * @param materialNameDe the German name of the row's catalog entry, or {@code null}; the picker JS
 *     shows it on a German page (REQ-P4K-023).
 */
@DtoMirror
public record MaterialExchangeReleasableItemDto(
    UUID inventoryItemId,
    @BackendEnumAsString String kind,
    String materialName,
    @BackendEnumAsString String quantityType,
    Integer quality,
    Double amount,
    String locationName,
    boolean alreadyReleased,
    boolean stolen,
    @Nullable String materialNameDe) {

  /**
   * Creates a picker entry without a German name.
   *
   * @param inventoryItemId the Lager row to release.
   * @param kind which offer kind releasing this row produces.
   * @param materialName the display name of the row's catalog entry.
   * @param quantityType the row's quantity unit name.
   * @param quality the row's quality, {@code null} for a game-item row.
   * @param amount the row's quantity in its {@code quantityType} unit.
   * @param locationName the row's location.
   * @param alreadyReleased whether an active offer already exists for this row.
   * @param stolen whether the row is marked „gestohlen".
   */
  public MaterialExchangeReleasableItemDto(
      UUID inventoryItemId,
      String kind,
      String materialName,
      String quantityType,
      Integer quality,
      Double amount,
      String locationName,
      boolean alreadyReleased,
      boolean stolen) {
    this(
        inventoryItemId,
        kind,
        materialName,
        quantityType,
        quality,
        amount,
        locationName,
        alreadyReleased,
        stolen,
        null);
  }
}
