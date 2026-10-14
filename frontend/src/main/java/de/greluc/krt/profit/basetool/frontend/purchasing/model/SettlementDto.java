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

package de.greluc.krt.profit.basetool.frontend.purchasing.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code SettlementDto}; must match it field for field.
 *
 * @param moneyReceived what the confirmed tranches put into the delegate's hands
 * @param moneyPaid what was paid the counterparty
 * @param remainder what returns to the funding account
 * @param lossAmount the part that bought nothing usable
 * @param lossReason why, or {@code null}
 * @param remainderRequestId the deposit request, or {@code null}
 * @param settledAt when the deal was settled, or {@code null}
 * @param version the optimistic-locking version
 */
@DtoMirror
public record SettlementDto(
    long moneyReceived,
    long moneyPaid,
    long remainder,
    long lossAmount,
    @BackendEnumAsString String lossReason,
    UUID remainderRequestId,
    Instant settledAt,
    Long version) {}
