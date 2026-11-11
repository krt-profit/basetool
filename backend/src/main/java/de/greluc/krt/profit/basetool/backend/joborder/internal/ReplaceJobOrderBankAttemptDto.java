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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.jetbrains.annotations.Nullable;

/**
 * A corrected full request for the same reported operation.
 *
 * @param revision the failed attempt being replaced
 * @param amount the actual principal to confirm
 * @param provision the corrected explicit additional funds, when changed
 * @param bank the corrected Bank coordinates with the same actual parties
 * @param outsideBank whether the reported payment factually occurred outside Bank custody
 */
public record ReplaceJobOrderBankAttemptDto(
    @Positive long revision,
    @NotBlank String amount,
    @Nullable String provision,
    @Valid @Nullable JobOrderBankSelection bank,
    @Nullable Boolean outsideBank) {}
