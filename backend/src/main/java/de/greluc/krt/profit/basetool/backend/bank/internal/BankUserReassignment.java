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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsReassignment;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank's part of the account merge: grants, view grants, approval limits and the custodian
 * registration follow the member, the bank trail and the acts recorded on bookings stay with the
 * source account, and two ledgers refuse the merge.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class BankUserReassignment extends OwnedRowsReassignment {

  static final int POSITION = 120;

  private final BankHolderRepository bankHolderRepository;

  /**
   * Declares the bank's columns.
   *
   * @param mover the engine that runs the statements
   * @param bankHolderRepository the custodian registry, probed for two ledgers
   */
  public BankUserReassignment(
      @NotNull OwnedRowsMover mover, @NotNull BankHolderRepository bankHolderRepository) {
    super(
        mover,
        POSITION,
        List.of(
            OwnedRows.deduped("bank_account_grant", "user_id", "account_id"),
            OwnedRows.deduped(
                "bank_account_view_grant", "grantee_user_id", "account_id", "grantee_kind"),
            OwnedRows.deduped(
                "bank_account_approval_limit", "grantee_user_id", "account_id", "grantee_kind"),
            OwnedRows.of("bank_holder", "user_id")),
        List.of(
            "bank_audit_event.actor_user_id",
            "bank_audit_event.target_user_id",
            "bank_account_grant.granted_by",
            "bank_booking_request.requested_by",
            "bank_booking_request.decided_by",
            "bank_booking_request.owner_approval_granted_by",
            "bank_booking_request.counterparty_user_id",
            "bank_transaction.initiated_by",
            "bank_transaction.counterparty_user_id"));
    this.bankHolderRepository = bankHolderRepository;
  }

  /**
   * Refuses the merge when both accounts carry a custodian row, since that is two ledgers rather
   * than a duplicate.
   *
   * @param source the account being emptied
   * @param target the account being kept
   * @throws BusinessConflictException when both hold a custodian row
   */
  @Override
  public void assertMergeable(@NotNull UUID source, @NotNull UUID target) {
    if (bankHolderRepository.existsByUserId(source)
        && bankHolderRepository.existsByUserId(target)) {
      throw new BusinessConflictException(
          "Both accounts hold a bank ledger. Merging two ledgers is an accounting decision and is"
              + " not done by this operation; settle or close one holder first.");
    }
  }
}
