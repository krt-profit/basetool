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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import java.util.UUID;

/**
 * Accounts available for the caller's current Order actions.
 *
 * @param collection the responsible unit's eligible collection accounts
 * @param designation the accounts the caller may designate
 * @param withdrawal the accounts the caller may withdraw from
 * @param transfer the accounts the caller may transfer from
 * @param settlementDeposit accounts available for a personal settlement deposit
 * @param custodians the custodians the caller may select
 */
@DtoMirror
public record JobOrderMoneyBankAccounts(
    List<JobOrderMoneyBankAccountCandidate> collection,
    List<JobOrderMoneyBankAccountCandidate> designation,
    List<JobOrderMoneyBankAccountCandidate> withdrawal,
    List<JobOrderMoneyBankAccountCandidate> transfer,
    List<JobOrderMoneyBankAccountCandidate> settlementDeposit,
    List<JobOrderMoneyBankCustodian> custodians) {

  /**
   * A registered custodian selectable for a player withdrawal.
   *
   * @param id the selectable custodian
   * @param userId the registered member who holds custody
   * @param name its display label
   */
  public record JobOrderMoneyBankCustodian(UUID id, UUID userId, String name) {}

  /**
   * An eligible account reference for an Order action.
   *
   * @param id the selectable account
   * @param name its display label
   * @param version the observed Order account holding revision
   */
  public record JobOrderMoneyBankAccountCandidate(UUID id, String name, long version) {}
}
