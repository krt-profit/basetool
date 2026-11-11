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
 * The backend's exact projection of the proposed provision.
 *
 * @param amount the provision in whole aUEC
 * @param holderName the current visible recipient
 * @param fee the fee in whole aUEC
 * @param bankHandling whether the provision uses Bank handling
 * @param resultingHolding the holder's resulting amount in whole aUEC
 */
@DtoMirror
public record JobOrderMoneyProvisionPreview(
    String amount,
    @Nullable String holderName,
    String fee,
    boolean bankHandling,
    String resultingHolding) {}
