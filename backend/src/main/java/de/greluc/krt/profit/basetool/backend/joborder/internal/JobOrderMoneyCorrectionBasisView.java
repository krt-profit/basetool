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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Current visible basis for revising one original fact.
 *
 * @param amount effective principal or provision
 * @param provision effective additional funds
 * @param holderIds canonical affected money positions
 * @param affectsTarget whether the correction changes the fulfilment basis
 * @param attributed whether an original commitment attribution may be removed
 * @param accountHoldingId the affected order-local account holding, if applicable
 */
public record JobOrderMoneyCorrectionBasisView(
    String amount,
    String provision,
    List<UUID> holderIds,
    boolean affectsTarget,
    boolean attributed,
    @Nullable UUID accountHoldingId) {}
