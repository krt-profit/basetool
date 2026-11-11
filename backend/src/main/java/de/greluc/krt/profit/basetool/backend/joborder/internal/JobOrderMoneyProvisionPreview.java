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
 * Exact effects of the proposed provision at its observed holder revision.
 *
 * @param amount exact new purpose-bound amount
 * @param holderName current permitted label of the credited holder
 * @param fee zero for a provision without a game transfer
 * @param bankHandling the chosen Bank handling
 * @param resultingHolding expected whole-aUEC amount for the selected holder
 * @param holderVersion observed holder or account-position revision
 * @param bank expected linked Bank effect
 */
public record JobOrderMoneyProvisionPreview(
    String amount,
    @Nullable String holderName,
    String fee,
    boolean bankHandling,
    String resultingHolding,
    long holderVersion,
    @Nullable JobOrderMoneyBankPreview bank) {}
