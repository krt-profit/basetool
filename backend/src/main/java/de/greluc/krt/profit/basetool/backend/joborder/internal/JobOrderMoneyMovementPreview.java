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

import org.jetbrains.annotations.Nullable;

/**
 * Calculates a movement's exact monetary effect at the submitted basis.
 *
 * @param amount net amount received
 * @param fee source-funded order cost
 * @param rate configured fee basis to echo on submission
 * @param provision selected new means
 * @param sourceAfter resulting available source funds for an adequately funded movement
 * @param sourceName the source holder's permitted label
 * @param destinationName the receiving party's permitted label
 * @param overpayment overpayment that would result from this handover
 * @param shortfall additional funds still required before booking
 */
public record JobOrderMoneyMovementPreview(
    String amount,
    String fee,
    String rate,
    String provision,
    String sourceAfter,
    @Nullable String sourceName,
    @Nullable String destinationName,
    String overpayment,
    String shortfall) {}
