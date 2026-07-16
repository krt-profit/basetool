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

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Create payload for a production booking ("Herstellung", REQ-ORDERS-025) against one ordered item
 * line: how many whole units were manufactured and, per required material, exactly which linked
 * inventory entries the material was drawn from. The {@code consumption} plan must cover the demand
 * ({@code perUnit × amount}) of every required material exactly, except for materials the operator
 * marked as not-to-be-booked-out in {@code skippedMaterialIds} (whose demand is dropped and whose
 * linked stock is left untouched).
 *
 * @param amount the whole units manufactured in this booking (≥ 1, ≤ the line's
 *     remaining-to-manufacture)
 * @param version the ordered item line's optimistic-lock version (echoed for the 409 guard)
 * @param consumption the per-inventory-entry material draws that must exactly cover the demand of
 *     every non-skipped required material; empty is allowed when the line has no derivable material
 *     requirements or every required material is skipped (nothing to consume)
 * @param skippedMaterialIds ids of required materials the operator opted out of booking out: their
 *     demand is excluded from the coverage check and no linked inventory is consumed for them.
 *     {@code null} is treated as none skipped
 * @param bookIn where and for whom the produced units are booked into the Lager as game-item stock
 *     (REQ-INV-032, design §5.6). <b>Transitional:</b> {@code null} keeps the legacy behaviour (no
 *     stock row is created — the pre-item-stock status quo) so the live Herstellung flow keeps
 *     working until the frontend modal ships the section; the follow-up frontend PR flips this to
 *     required
 */
public record JobOrderItemProductionCreateDto(
    @NotNull @Min(1) Integer amount,
    @NotNull Long version,
    @NotNull List<@Valid JobOrderItemProductionConsumptionDto> consumption,
    List<UUID> skippedMaterialIds,
    @Valid BookInDto bookIn) {

  /**
   * The production book-in target (REQ-INV-032): the location, owner and org-unit pool the produced
   * game-item stock lands on, plus the personal flag and the auto-earmark opt-out.
   *
   * @param locationId the storage location the produced units are booked in at ("wo"); required
   * @param ownerUserId the user the stock row is created for ("bei wem"); {@code null} defaults to
   *     the acting user
   * @param owningOrgUnitId the org-unit picker output whose stock pool the row is stamped onto
   *     (REQ-ORG-004/016 create-on-behalf semantics, validated against the owner's memberships);
   *     {@code null} triggers the auto-stamp path
   * @param personal {@code true} books the units into the owner's personal pool; {@code null} is
   *     treated as {@code false}. Mutually exclusive with {@code allocateToOrder} — personal stock
   *     never carries allocations
   * @param allocateToOrder {@code true} (also the {@code null} default) auto-earmarks the produced
   *     units to the producing order via a job-order allocation slice; must be explicitly {@code
   *     false} when {@code personal} is set
   */
  public record BookInDto(
      @NotNull UUID locationId,
      UUID ownerUserId,
      UUID owningOrgUnitId,
      Boolean personal,
      Boolean allocateToOrder) {}
}
