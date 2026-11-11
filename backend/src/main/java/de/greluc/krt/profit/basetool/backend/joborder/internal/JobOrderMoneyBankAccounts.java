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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.CustodianRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Authorized candidate groups with no Bank balances.
 *
 * @param collection accounts for deposits
 * @param settlementDeposit accounts for an authorized settlement deposit
 * @param designation accounts for requester designation
 * @param withdrawal accounts for provision from Bank funds
 * @param transfer accounts for active transfers to a designated account
 * @param custodians selectable registered custodians
 */
public record JobOrderMoneyBankAccounts(
    List<Candidate> collection,
    List<Candidate> settlementDeposit,
    List<Candidate> designation,
    List<Candidate> withdrawal,
    List<Candidate> transfer,
    List<CustodianRef> custodians) {

  /**
   * Account reference with the observed scoped position revision.
   *
   * @param id the Bank account
   * @param name the account label
   * @param version current Order account position revision
   */
  @Schema(name = "JobOrderMoneyBankAccountCandidate")
  public record Candidate(UUID id, String name, long version) {}
}
