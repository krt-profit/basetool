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

import de.greluc.krt.profit.basetool.backend.model.CheckoutType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Data transfer record carrying Inventory Item Book Out payload.
 *
 * <p>R5.d.g added the trailing {@link #targetOwningOrgUnitId} picker output. Applies only to the
 * {@link CheckoutType#TRANSFER} branch — the cross-user transfer flow lands the new {@link
 * de.greluc.krt.profit.basetool.backend.model.InventoryItem} row on the picked org unit instead of
 * the pool the destination user would otherwise be auto-stamped into. The service routes the stamp
 * through {@code OwnerScopeService.resolveOrgUnitForPickerOutputNullable(targetUser,
 * targetOwningOrgUnitId)} — which delegates to {@code OrgUnitStampingService.resolveStampedOrgUnit}
 * and therefore accepts <b>all four</b> org-unit kinds: Staffel, Spezialkommando, Bereich and
 * Organisationsleitung. (The strict, Staffel-only {@code resolveSquadronForPickerOutput} is
 * <em>not</em> on this path, so its "Spezialkommando ownership of this aggregate is not yet
 * supported" rejection never fires here.) The Umbuchen picker matches that acceptance rule by
 * fetching {@code /users/&#123;id&#125;/memberships?allKinds=true}.
 *
 * <p>A non-null pick is honoured when it is one of the <em>destination</em> user's DIRECT
 * memberships — intentional cross-org-unit semantics per plan §D4: User A from Staffel-X may book
 * out into User B's Spezialkommando-Y stock as long as User B is a member of Y — <em>or</em> when
 * it is an org unit the current <b>caller</b> may edit ({@code AccessGateService.canEditOrgUnit},
 * cascade-aware), the create-on-behalf widening of epic #692 Phase 4 / REQ-ORG-016 that lets a
 * Bereichsleitung/OL place the recipient's row in a subordinate unit they oversee. A pick that is
 * neither is rejected with 400.
 *
 * <p>When {@code null}, the resolver auto-stamps a single-membership target, honours an
 * active-context pin onto one of the target's own units (REQ-ORG-017 "pin, else choose"), rejects a
 * multi-membership target that has neither with 400, and yields an ownerless row ({@code
 * owningOrgUnit == null}, legal since V132) for a membershipless target.
 *
 * <p>Ignored for {@link CheckoutType#DISCARD} and {@link CheckoutType#SELL} — both terminate the
 * inventory row and never create a new ownership stamp.
 *
 * <p>{@link #mergeStock} is the per-action stock-merge opt-in (REQ-INV-026) and applies only to the
 * {@link CheckoutType#TRANSFER} branch — the moved quantity lands as a new row at the target, and a
 * {@code PIECE} material merges it into a matching target stack unconditionally while an {@code
 * SCU} material merges only when this flag is {@code true}. It is never persisted and governs only
 * this one transfer.
 *
 * <p>{@link #jobOrderReductions} and {@link #missionReductions} are the Variante-C "deduct from"
 * plan (REQ-INV-027): because an entry's job-order and mission splits are two independent taggings
 * of the same stock, the single deducted {@link #amount} is sourced separately per dimension. Each
 * list names the earmark slices to shrink and by how much; whatever a dimension's reductions leave
 * uncovered is taken from that dimension's not-yet-assigned rest. A {@code null} / empty list means
 * "take it all from the rest" (the legacy behaviour, which 422s when the rest is too small). On a
 * {@link CheckoutType#TRANSFER} the reduced tags move to the new target row (the moved stock stays
 * earmarked); on a {@link CheckoutType#SELL} the mission reductions additionally drive the proceeds
 * split — mission {@code j} is credited {@code sellAmount × amount_j / amount} of the sale (for
 * missions the seller participates in), the rest staying the seller's personal proceeds, so no
 * separate income-attribution input exists any more.
 */
public record InventoryItemBookOutDto(
    @NotNull @Min(0) Double amount,
    UUID targetUserId,
    UUID targetLocationId,
    CheckoutType type,
    String terminal,
    @Min(0) BigDecimal sellAmount,
    @NotNull Long version,
    @Nullable UUID targetOwningOrgUnitId,
    Boolean mergeStock,
    @Nullable List<@Valid AllocationReductionDto> jobOrderReductions,
    @Nullable List<@Valid AllocationReductionDto> missionReductions) {}
