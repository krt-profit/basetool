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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Expected Bank routing and exact funding at the observed terms.
 *
 * @param disposition whether the terms would require a pending request
 * @param actualFee whole-aUEC game fee
 * @param feeFunding sole fee payer
 * @param bankSourceDebit exact debit of the Bank funding account
 * @param orderSourceCharge principal and fee consumed from the Order source
 * @param rate configured fee basis
 */
@Schema(name = "JobOrderMoneyBankPreview")
public record JobOrderMoneyBankPreview(
    Disposition disposition,
    String actualFee,
    JobOrderMoneyFeeFunding feeFunding,
    String bankSourceDebit,
    String orderSourceCharge,
    String rate) {

  /** Synchronous admission before any Bank decision is made. */
  public enum Disposition {
    /** Current authority would execute the operation. */
    DIRECT,
    /** The operation would need a later Bank decision. */
    PENDING
  }
}
