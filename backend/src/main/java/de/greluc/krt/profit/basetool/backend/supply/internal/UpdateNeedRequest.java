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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.supply.api.NeedPriority;
import de.greluc.krt.profit.basetool.backend.supply.api.PurchaseReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Edits an open or paused need (REQ-SUP-003). The catalogue entry and the tier are fixed: a
 * different one is a new need. PUT semantics: an omitted optional field clears it.
 *
 * @param amount the new amount, not below what is already covered
 * @param priority the priority, {@code NORMAL} when omitted
 * @param purchasable whether buying is allowed
 * @param purchaseReason why buying is allowed, required when purchasable
 * @param maxUnitPrice the highest whole-aUEC unit price, required when purchasable
 * @param jobOrderId the unit's job order the need feeds, or {@code null}
 * @param version the echoed optimistic-locking version
 */
public record UpdateNeedRequest(
    @NotNull @Positive Double amount,
    @Nullable NeedPriority priority,
    boolean purchasable,
    @Nullable PurchaseReason purchaseReason,
    @Nullable @Positive Long maxUnitPrice,
    @Nullable UUID jobOrderId,
    @NotNull Long version) {}
