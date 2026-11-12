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

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Participant-readable effect of a linked financial correction.
 *
 * @param originalOperationId the preserved original fact
 * @param previousAmount its effective net amount before the correction
 * @param amount its effective net amount after the correction
 * @param previousProvision its effective provision before the correction
 * @param provision its effective provision after the correction
 * @param previousFee its effective fee before the correction
 * @param fee its effective fee after the correction
 * @param commitmentId the original attributed occurrence, if present
 * @param previousCredit provision credited to that occurrence before the correction
 * @param credit provision credited to that occurrence after the correction
 * @param attributionRemoved whether the original attribution has been explicitly corrected away
 */
public record JobOrderMoneyCorrectionView(
    UUID originalOperationId,
    String previousAmount,
    String amount,
    String previousProvision,
    String provision,
    String previousFee,
    String fee,
    @Nullable UUID commitmentId,
    @Nullable String previousCredit,
    @Nullable String credit,
    boolean attributionRemoved) {}
