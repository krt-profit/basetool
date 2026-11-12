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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import org.jetbrains.annotations.Nullable;

/**
 * The server's proposed exact money movement.
 *
 * @param amount the net principal
 * @param fee the fee in whole aUEC
 * @param rate the applied fee rate
 * @param provision the additional funds to be reported
 * @param sourceAfter the source holding after the movement
 * @param sourceName the source holder label
 * @param destinationName the destination label
 * @param overpayment the projected amount over the target
 * @param shortfall the additional amount needed to fund the draft
 * @param destinationAfter the receiving order holding after the movement, if applicable
 * @param completes whether the applied effect fulfils the order
 * @param reopens whether the applied effect reopens the order
 * @param bank the authorised linked Bank effects
 */
@DtoMirror
public record JobOrderMoneyMovementPreview(
    String amount,
    String fee,
    String rate,
    String provision,
    String sourceAfter,
    @Nullable String sourceName,
    @Nullable String destinationName,
    String overpayment,
    String shortfall,
    @Nullable String destinationAfter,
    boolean completes,
    boolean reopens,
    @Nullable JobOrderMoneyBankPreview bank) {}
