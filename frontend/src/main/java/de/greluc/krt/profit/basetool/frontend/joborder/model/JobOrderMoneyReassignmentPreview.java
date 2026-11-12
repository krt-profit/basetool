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
 * Exact balances and charges for a proposed cross-order purpose change.
 *
 * @param amount the amount moved
 * @param fee the exact fee of zero
 * @param provision the exact new provision of zero
 * @param sourceAfter the source holder's resulting balance
 * @param destinationAfter the destination holder's resulting balance
 * @param sourceName the permitted current sending holder label
 * @param destinationName the permitted current receiving holder label
 */
@DtoMirror
public record JobOrderMoneyReassignmentPreview(
    String amount,
    String fee,
    String provision,
    String sourceAfter,
    String destinationAfter,
    @Nullable String sourceName,
    @Nullable String destinationName) {}
