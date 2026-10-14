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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.purchasing.api.LossReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.jetbrains.annotations.Nullable;

/**
 * Records the settlement of a deal (REQ-PUR-017); saving it again while the remainder is not yet
 * back replaces the figures.
 *
 * @param moneyPaid what the delegate paid the counterparty
 * @param lossAmount the part of it that bought nothing usable
 * @param lossReason why, required with a loss
 * @param version the echoed version of the settlement, {@code null} for the first save
 */
public record SettleDealRequest(
    @NotNull @PositiveOrZero Long moneyPaid,
    @NotNull @PositiveOrZero Long lossAmount,
    @Nullable LossReason lossReason,
    @Nullable Long version) {}
