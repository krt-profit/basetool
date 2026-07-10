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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Data transfer record carrying Inventory Item Book Out payload.
 *
 * <p>R5.d.g added the trailing {@link #targetOwningOrgUnitId} picker output. Applies only to the
 * {@link CheckoutType#TRANSFER} branch — the cross-user transfer flow lands the new {@link
 * de.greluc.krt.profit.basetool.backend.model.InventoryItem} row on the picked org unit instead of
 * the destination user's home Staffel. The service routes the stamp through {@code
 * OwnerScopeService.resolveSquadronForPickerOutput(targetUser, targetOwningOrgUnitId)} — the
 * resolver validates the picked OrgUnit against the *destination* user's memberships (intentional
 * cross-org-unit semantics per plan §D4: User A from Staffel-X may book out into User B's
 * Spezialkommando-Y stock as long as User B is a member of Y). Spezialkommando selections are still
 * refused with 400 until the destructive cleanup release loosens NOT NULL on the legacy {@code
 * owning_squadron_id} column.
 *
 * <p>Ignored for {@link CheckoutType#DISCARD} and {@link CheckoutType#SELL} — both terminate the
 * inventory row and never create a new ownership stamp.
 */
public record InventoryItemBookOutDto(
    @NotNull @Min(0) Double amount,
    UUID targetUserId,
    UUID targetLocationId,
    CheckoutType type,
    String terminal,
    @Min(0) BigDecimal sellAmount,
    @NotNull Long version,
    @Nullable UUID targetOwningOrgUnitId) {}
