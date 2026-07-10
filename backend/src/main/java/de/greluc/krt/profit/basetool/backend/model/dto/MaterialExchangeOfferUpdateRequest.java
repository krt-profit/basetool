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

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Write payload for editing an existing offer ("Angebot bearbeiten", REQ-MARKET-007). Carries the
 * new offered quantity and trade remark. Only the owner may edit; the echoed {@code version} guards
 * against a concurrent edit via the {@code support.OptimisticLock} check (409 on mismatch). The
 * offered amount may be raised or lowered but must stay positive and at most the linked item's
 * current stock (checked server-side, ADR-0086).
 *
 * @param offeredAmount the new SCU quantity to offer; must be positive and at most the item's
 *     current amount.
 * @param remark the new free-form Markdown remark, at most 20 000 characters (may be blank).
 * @param version the optimistic-lock version the client last saw.
 */
public record MaterialExchangeOfferUpdateRequest(
    @NotNull @Positive Double offeredAmount,
    @Size(max = 20000) String remark,
    @NotNull @Min(0) Long version) {}
