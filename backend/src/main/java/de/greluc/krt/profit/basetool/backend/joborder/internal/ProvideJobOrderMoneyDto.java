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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A submitted report of actual, purpose-bound money.
 *
 * @param operationId stable caller-generated logical operation identity
 * @param amount exact whole-aUEC amount
 * @param bankHandling explicit per-operation choice
 * @param holderVersion version of the chosen holder, zero before the first provision
 * @param holderId explicitly chosen existing holder
 * @param holderUserId explicitly selected member account
 * @param holderName new accountless person, with no account inferred from their name
 * @param commitmentId explicitly selected unit commitment occurrence
 * @param bank the explicit Bank selection when handling is selected
 * @param rate the fee basis observed in the linked provision preview
 */
public record ProvideJobOrderMoneyDto(
    @NotNull UUID operationId,
    @NotBlank String amount,
    @NotNull Boolean bankHandling,
    @Nullable Long holderVersion,
    @Nullable UUID holderId,
    @Nullable UUID holderUserId,
    @Nullable @Size(max = 200) String holderName,
    @Nullable UUID commitmentId,
    @Valid @Nullable JobOrderBankSelection bank,
    @Nullable @Size(max = 24) String rate) {

  JobOrderMoneySelection selection() {
    return JobOrderMoneySelection.parse(holderId, holderUserId, holderName);
  }
}
