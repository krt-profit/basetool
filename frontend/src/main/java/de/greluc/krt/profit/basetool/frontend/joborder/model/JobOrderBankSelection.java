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

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The Bank representation and actual parties selected for one order operation.
 *
 * @param type the Bank effect
 * @param accountId the principal account
 * @param custodianId the registered custodian
 * @param targetAccountId the receiving account for a transfer
 * @param targetCustodianId the receiving registered custodian
 * @param paymentReported whether a player payment occurred
 * @param orderFundedDepositFee whether order funds covered the depositor's fee
 * @param counterpartyUserId the registered depositor
 * @param counterpartyName the accountless depositor's name
 * @param justification the debit reason required by Bank
 * @param accountOnly whether the order uses a Bank account holding
 */
@DtoMirror
public record JobOrderBankSelection(
    @NotNull @BackendEnumAsString String type,
    @Nullable UUID accountId,
    @Nullable UUID custodianId,
    @Nullable UUID targetAccountId,
    @Nullable UUID targetCustodianId,
    @Nullable Boolean paymentReported,
    @Nullable Boolean orderFundedDepositFee,
    @Nullable UUID counterpartyUserId,
    @Nullable @Size(max = 200) String counterpartyName,
    @Nullable @Size(max = 1000) String justification,
    @Nullable Boolean accountOnly) {}
