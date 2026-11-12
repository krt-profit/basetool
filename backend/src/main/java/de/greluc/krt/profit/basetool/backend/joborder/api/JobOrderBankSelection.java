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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Explicit Bank coordinates for one submitted order action.
 *
 * @param type the economic Bank representation
 * @param accountOnly whether the order position belongs to a Bank account
 * @param accountId the selected principal account
 * @param custodianId the actual source custodian or the custodian receiving a deposit
 * @param targetAccountId the receiving account for an account transfer
 * @param targetCustodianId the receiving custodian for a transfer
 * @param paymentReported whether a transfer between players has occurred
 * @param orderFundedDepositFee whether the order paid the depositor's fee
 * @param counterpartyUserId the actual depositor's registered identity for new provision
 * @param counterpartyName the actual depositor's explicit name for new provision
 * @param justification the debit justification passed to the Bank
 */
public record JobOrderBankSelection(
    @NotNull Type type,
    @Nullable Boolean accountOnly,
    @Nullable UUID accountId,
    @Nullable UUID custodianId,
    @Nullable UUID targetAccountId,
    @Nullable UUID targetCustodianId,
    @Nullable Boolean paymentReported,
    @Nullable Boolean orderFundedDepositFee,
    @Nullable UUID counterpartyUserId,
    @Nullable @Size(max = 200) String counterpartyName,
    @Nullable @Size(max = 1000) String justification) {

  /** Representation selected for the corresponding Bank effect. */
  public enum Type {
    /** Principal enters Bank custody. */
    DEPOSIT,
    /** Principal leaves Bank custody. */
    WITHDRAWAL,
    /** The owner account changes. */
    TRANSFER,
    /** Only the custodian changes. */
    HOLDER_TRANSFER,
    /** Existing funds are approved without a ledger posting. */
    AUTHORISATION
  }
}
