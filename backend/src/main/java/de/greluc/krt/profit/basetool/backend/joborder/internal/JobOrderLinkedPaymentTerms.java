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

import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy;
import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy.RateSnapshot;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.Counterparty;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Normalizes selected Bank terms and attributes the reported game fee once. */
@Component
@RequiredArgsConstructor
class JobOrderLinkedPaymentTerms {
  private final TransferFeePolicy fees;

  static boolean reported(JobOrderBankSelection bank) {
    return switch (bank.type()) {
      case DEPOSIT, HOLDER_TRANSFER -> true;
      case AUTHORISATION -> false;
      case TRANSFER, WITHDRAWAL -> Boolean.TRUE.equals(bank.paymentReported());
    };
  }

  static @Nullable String normalizedCounterparty(JobOrderBankSelection bank) {
    return StringNormalization.trimToNull(bank.counterpartyName());
  }

  static void validate(JobOrderBankSelection bank) {
    if (Boolean.TRUE.equals(bank.accountOnly())) {
      validateAccountOnly(bank);
      return;
    }
    JobOrderBankSelection.Type type = bank.type();
    if (type == null
        || bank.custodianId() == null
        || (type == JobOrderBankSelection.Type.HOLDER_TRANSFER) != (bank.accountId() == null)
        || (type == JobOrderBankSelection.Type.TRANSFER) != (bank.targetAccountId() != null)
        || (type == JobOrderBankSelection.Type.TRANSFER
                || type == JobOrderBankSelection.Type.HOLDER_TRANSFER)
            != (bank.targetCustodianId() != null)
        || ((type == JobOrderBankSelection.Type.TRANSFER
                || type == JobOrderBankSelection.Type.WITHDRAWAL)
            && bank.paymentReported() == null)
        || (bank.paymentReported() != null && bank.paymentReported() != reported(bank))
        || (type != JobOrderBankSelection.Type.DEPOSIT
            && (bank.counterpartyName() != null
                || bank.counterpartyUserId() != null
                || bank.orderFundedDepositFee() != null))) {
      throw invalid();
    }
  }

  private static void validateAccountOnly(JobOrderBankSelection bank) {
    var type = bank.type();
    if (type == null
        || bank.accountId() == null
        || bank.custodianId() != null
        || bank.targetCustodianId() != null
        || bank.orderFundedDepositFee() != null
        || type == JobOrderBankSelection.Type.WITHDRAWAL
        || type == JobOrderBankSelection.Type.HOLDER_TRANSFER
        || (type == JobOrderBankSelection.Type.TRANSFER) != (bank.targetAccountId() != null)
        || bank.accountId().equals(bank.targetAccountId())
        || (type != JobOrderBankSelection.Type.DEPOSIT
            && (bank.counterpartyName() != null
                || bank.counterpartyUserId() != null
                || Boolean.TRUE.equals(bank.paymentReported())))
        || (type == JobOrderBankSelection.Type.DEPOSIT
            && Boolean.FALSE.equals(bank.paymentReported()))) {
      throw invalid();
    }
  }

  static JobOrderBankSelection resolveAccountTransfer(
      JobOrderBankSelection bank, @Nullable UUID source, @Nullable UUID destination) {
    if (source == null
        || destination == null
        || (bank.accountId() != null && !source.equals(bank.accountId()))
        || (bank.targetAccountId() != null && !destination.equals(bank.targetAccountId()))) {
      throw invalid();
    }
    var resolved =
        new JobOrderBankSelection(
            bank.type(),
            bank.accountOnly(),
            source,
            bank.custodianId(),
            destination,
            bank.targetCustodianId(),
            bank.paymentReported(),
            bank.orderFundedDepositFee(),
            bank.counterpartyUserId(),
            bank.counterpartyName(),
            bank.justification());
    validate(resolved);
    return resolved;
  }

  static Counterparty depositor(JobOrderBankSelection bank) {
    String name = normalizedCounterparty(bank);
    if ((bank.counterpartyUserId() == null) == (name == null)
        || (name != null && HandleAnonymisation.isReserved(name))) {
      throw invalid();
    }
    return new Counterparty(bank.counterpartyUserId(), name);
  }

  Fee feeFor(
      JobOrderMoneyKind kind, JobOrderBankSelection bank, BigDecimal amount, RateSnapshot rate) {
    if (!reported(bank)) {
      return new Fee(BigDecimal.ZERO, JobOrderMoneyFeeFunding.NONE);
    }
    JobOrderMoneyFeeFunding funding =
        switch (bank.type()) {
          case HOLDER_TRANSFER -> JobOrderMoneyFeeFunding.KRT;
          case DEPOSIT ->
              Boolean.TRUE.equals(bank.orderFundedDepositFee())
                  ? JobOrderMoneyFeeFunding.ORDER
                  : JobOrderMoneyFeeFunding.DEPOSITOR;
          case WITHDRAWAL ->
              kind == JobOrderMoneyKind.PROVISION
                  ? JobOrderMoneyFeeFunding.BANK_SOURCE
                  : JobOrderMoneyFeeFunding.ORDER;
          case TRANSFER -> JobOrderMoneyFeeFunding.ORDER;
          case AUTHORISATION -> throw invalid();
        };
    return new Fee(fees.feeOn(amount, rate), funding);
  }

  static BadRequestException invalid() {
    return new BadRequestException("orders.money.bank.termsInvalid");
  }

  /** A calculated game fee with its sole funding source. */
  record Fee(BigDecimal amount, JobOrderMoneyFeeFunding funding) {
    BigDecimal orderFee() {
      return funding == JobOrderMoneyFeeFunding.ORDER ? amount : BigDecimal.ZERO;
    }
  }
}
