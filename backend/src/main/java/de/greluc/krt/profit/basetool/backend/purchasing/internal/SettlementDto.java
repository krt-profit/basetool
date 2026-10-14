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
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The settlement of a deal (REQ-PUR-017).
 *
 * @param moneyReceived what the confirmed tranches put into the delegate's hands
 * @param moneyPaid what the delegate paid the counterparty
 * @param remainder what returns to the funding account
 * @param lossAmount the part of the paid money that bought nothing usable
 * @param lossReason why, or {@code null} without a loss
 * @param remainderRequestId the deposit request that returns the remainder, or {@code null}
 * @param settledAt when the deal was settled, or {@code null} while the remainder is on its way
 * @param version the optimistic-locking version to echo
 */
public record SettlementDto(
    long moneyReceived,
    long moneyPaid,
    long remainder,
    long lossAmount,
    @Nullable LossReason lossReason,
    @Nullable UUID remainderRequestId,
    @Nullable Instant settledAt,
    Long version) {}
