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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import org.jetbrains.annotations.Nullable;

/**
 * Exact retained-basis terms of a proposed repair.
 *
 * @param revision the failed revision being repaired
 * @param amount the corrected principal
 * @param actualFee the retained-basis fee for the corrected amount
 * @param feeFunding the sole fee payer
 * @param orderSourceCharge the amount bound on the order source
 * @param rate the frozen fee basis
 * @param bank the authorized Bank routing and source debit when selected
 */
@DtoMirror
public record JobOrderMoneyRepairQuote(
    long revision,
    String amount,
    String actualFee,
    @BackendEnumAsString String feeFunding,
    String orderSourceCharge,
    String rate,
    @Nullable JobOrderMoneyBankPreview bank) {}
