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

package de.greluc.krt.profit.basetool.backend.model.dto;

import de.greluc.krt.profit.basetool.backend.validation.QuantityAware;
import de.greluc.krt.profit.basetool.backend.validation.ValidQuantityAmount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Data transfer record carrying Inventory Item Create payload.
 *
 * <p>The optional {@link #owningOrgUnitId} field is the R5.d picker output: when present, it tells
 * the service which {@link de.greluc.krt.profit.basetool.backend.model.OrgUnit} the new inventory
 * row should be stamped onto. The service validates that the resolved org unit is one the target
 * user actually belongs to (looked up via {@code OrgUnitMembershipRepository}). When {@code null},
 * the service falls back to the legacy "stamp the target user's home Staffel" behaviour — every
 * user has exactly one Staffel membership today, so the field is effectively optional for the
 * single-membership case and required only once users belong to multiple org units.
 *
 * @param userId target user the inventory row is created for; may be {@code null} for self-entries
 *     (the service substitutes the JWT subject).
 * @param materialId Material UUID for a material row; exactly one of {@code materialId} / {@code
 *     gameItemId} must be set (REQ-INV-029).
 * @param gameItemId GameItem UUID for a game-item stock row (REQ-INV-029, ADR-0101); mutually
 *     exclusive with {@code materialId}. A game-item row carries no quality, holds positive
 *     whole-unit amounts and rejects mission references.
 * @param locationId Storage location UUID; required.
 * @param quality Quality percentage in {@code [0, 1000]}; required for a material row, forbidden
 *     for a game-item row.
 * @param amount Quantity; required, non-negative (further constrained per catalog kind via {@link
 *     de.greluc.krt.profit.basetool.backend.validation.ValidQuantityAmount}).
 * @param personal {@code true} marks the row as a personal entry not visible in the global Lager
 *     view; cannot be combined with mission/job-order references.
 * @param missionId optional mission reference.
 * @param jobOrderId optional job-order reference.
 * @param owningOrgUnitId optional R5.d owner-picker output: the {@link
 *     de.greluc.krt.profit.basetool.backend.model.OrgUnit} on whose stock this row should land.
 *     When present, must point at an org unit the target user is a member of (validated
 *     server-side). When {@code null}, the service stamps the target user's home Staffel —
 *     preserving today's behaviour for the single-membership case.
 * @param mergeStock per-action stock-merge opt-in (REQ-INV-026). For a {@code PIECE} material the
 *     new row is merged into a matching existing stack unconditionally, so this flag is ignored;
 *     for an {@code SCU} material the merge happens only when this is {@code true} — the user ticks
 *     the modal checkbox for this single book-in. {@code null} is treated as {@code false}. It is
 *     never persisted: it governs only this one transaction.
 * @param jobOrderAllocations optional Variante-C split-at-check-in (REQ-INV-027, R4): earmark parts
 *     of the new entry to several job orders with their own amounts. When non-empty it supersedes
 *     {@link #jobOrderId}; the Σ of the amounts must stay within {@link #amount} (R5) and every
 *     target's material requirement is checked. {@code null}/empty falls back to the single {@link
 *     #jobOrderId}.
 * @param missionAllocations optional Variante-C split-at-check-in for missions — the mission
 *     counterpart of {@link #jobOrderAllocations}; supersedes {@link #missionId} when non-empty.
 */
@ValidQuantityAmount
public record InventoryItemCreateDto(
    UUID userId,
    UUID materialId,
    UUID gameItemId,
    @NotNull UUID locationId,
    @Min(0) @Max(1000) Integer quality,
    @NotNull Double amount,
    Boolean personal,
    UUID missionId,
    UUID jobOrderId,
    UUID owningOrgUnitId,
    Boolean mergeStock,
    List<@Valid InventoryAllocationInput> jobOrderAllocations,
    List<@Valid InventoryAllocationInput> missionAllocations)
    implements QuantityAware {

  /**
   * Bean-validation guard for the catalog XOR (REQ-INV-029): exactly one of {@link #materialId} /
   * {@link #gameItemId} must be present — mirrors the DB CHECK {@code
   * chk_inventory_item_catalog_xor} so the violation surfaces as a 400 validation error instead of
   * a 500 integrity failure.
   *
   * @return {@code true} when exactly one catalog reference is set
   */
  @AssertTrue(message = "{error.validation.inventory_catalog_xor}")
  public boolean isCatalogReferenceValid() {
    return (materialId == null) != (gameItemId == null);
  }

  /**
   * Bean-validation guard pairing {@link #quality} with the catalog kind (REQ-INV-029): a material
   * row requires a quality, a game-item row forbids one. Skipped while the XOR guard above already
   * fails, so a payload with neither reference reports only the XOR violation.
   *
   * @return {@code true} when the quality presence matches the catalog kind
   */
  @AssertTrue(message = "{error.validation.inventory_quality_by_kind}")
  public boolean isQualityConsistentWithCatalog() {
    if ((materialId == null) == (gameItemId == null)) {
      return true;
    }
    return materialId != null ? quality != null : quality == null;
  }

  /**
   * Bean-validation guard rejecting mission references on a game-item payload (REQ-INV-031): item
   * rows are allocatable only to ITEM job orders, never to missions.
   *
   * @return {@code true} when a game-item payload carries no mission reference
   */
  @AssertTrue(message = "{error.validation.inventory_item_no_mission}")
  public boolean isMissionFreeForGameItem() {
    return gameItemId == null
        || (missionId == null && (missionAllocations == null || missionAllocations.isEmpty()));
  }
}
