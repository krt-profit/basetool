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
 * The authorised Bank effects of proposed Order money terms.
 *
 * @param disposition the expected Bank routing
 * @param actualFee the exact game fee
 * @param feeFunding the sole fee payer
 * @param bankSourceDebit the expected debit to the selected Bank source
 * @param orderSourceCharge the exact charge against Order funds
 * @param rate the observed fee rate
 */
@DtoMirror
public record JobOrderMoneyBankPreview(
    @BackendEnumAsString String disposition,
    String actualFee,
    @BackendEnumAsString String feeFunding,
    String bankSourceDebit,
    String orderSourceCharge,
    @Nullable String rate) {}
