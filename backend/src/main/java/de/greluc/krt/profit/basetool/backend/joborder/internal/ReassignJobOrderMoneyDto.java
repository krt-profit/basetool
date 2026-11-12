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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Terms and observed holder revisions for a purpose change across orders.
 *
 * @param operationId persistent identity required to book
 * @param sourceHolderId the debited holder
 * @param sourceVersion the observed source holder revision
 * @param destinationOrderId the receiving order
 * @param destinationHolderId explicitly selected receiving holder, when applicable
 * @param destinationVersion the observed receiving holder revision, or zero when absent
 * @param confirmSamePerson explicit confirmation for a selected pair of named accountless holders
 * @param amount the exact amount assigned in whole aUEC
 * @param bankHandling the explicit Bank-free choice
 */
public record ReassignJobOrderMoneyDto(
    @Nullable UUID operationId,
    @NotNull UUID sourceHolderId,
    @Nullable Long sourceVersion,
    @NotNull UUID destinationOrderId,
    @Nullable UUID destinationHolderId,
    @Nullable Long destinationVersion,
    @Nullable Boolean confirmSamePerson,
    @NotBlank String amount,
    @NotNull Boolean bankHandling) {}
