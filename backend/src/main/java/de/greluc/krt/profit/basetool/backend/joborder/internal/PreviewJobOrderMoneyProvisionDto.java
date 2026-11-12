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
 * Inputs for showing a provision's exact effect without submitting a logical operation.
 *
 * @param amount exact whole-aUEC provision
 * @param bankHandling chosen Bank handling for this operation
 * @param holderVersion observed independent holder revision
 * @param holderId explicitly selected existing holder
 * @param holderUserId explicitly selected member account
 * @param holderName newly named accountless holder
 * @param commitmentId explicitly selected unit commitment occurrence
 * @param bank selected Bank effect and actual parties
 */
public record PreviewJobOrderMoneyProvisionDto(
    @NotBlank String amount,
    @NotNull Boolean bankHandling,
    @Nullable Long holderVersion,
    @Nullable UUID holderId,
    @Nullable UUID holderUserId,
    @Nullable @Size(max = 200) String holderName,
    @Nullable UUID commitmentId,
    @Valid @Nullable JobOrderBankSelection bank) {

  JobOrderMoneySelection selection() {
    return JobOrderMoneySelection.parse(holderId, holderUserId, holderName);
  }
}
