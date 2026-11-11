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
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Editable facts of one failed attempt under current order authority.
 *
 * @param revision the failed Bank attempt revision
 * @param amount the previously reported principal
 * @param provision the retained explicit additional provision
 * @param kind the order action being repaired
 * @param type the Bank representation of the original action
 * @param actualPayment whether the original game payment was reported
 * @param actualFee the retained game fee
 * @param rate the frozen fee basis
 * @param feeFunding the retained fee payer
 * @param outsideBankAllowed whether the exact facts may be recorded independently
 * @param accountChangeAllowed whether the principal account is editable
 * @param accountId the original debit or deposit account when independently visible
 * @param accounts permitted alternative accounts for this repair
 */
@DtoMirror
public record JobOrderMoneyRepairView(
    long revision,
    String amount,
    String provision,
    @BackendEnumAsString String kind,
    @BackendEnumAsString String type,
    boolean actualPayment,
    String actualFee,
    String rate,
    @BackendEnumAsString String feeFunding,
    boolean outsideBankAllowed,
    boolean accountChangeAllowed,
    @Nullable UUID accountId,
    List<RepairAccount> accounts) {

  /**
   * Selectable Bank account reference.
   *
   * @param id the authorized account identity
   * @param name the authorized account label
   */
  public record RepairAccount(UUID id, String name) {}
}
