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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.Counterparty;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankSelection;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Checks the retained payment against current order parties under the prepared order guard. */
@Component
@RequiredArgsConstructor
class JobOrderLinkedPartyIntegrity {
  private final JobOrderLinkedMoneyCommands commands;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyRecipients recipients;

  void requireCurrent(JobOrder order, JobOrderLinkedMoneyAttempt attempt) {
    UUID orderId = order.getId();
    if (attempt.getAccountHoldingId() != null) {
      requireCurrentAccountParties(orderId, attempt);
      return;
    }
    var source = canonicalHolder(orderId, attempt.personalHolderId());
    UUID destinationId = attempt.getDestinationHolderId();
    var destination = destinationId == null ? null : canonicalHolder(orderId, destinationId);
    UUID recipientId = attempt.getRecipientId();
    var designation = recipientId == null ? null : recipients.require(orderId, recipientId);
    if (designation != null
        && (designation.isSuperseded()
            || (attempt.getKind().delivers()
                && !attempt.isActualPayment()
                && !designation.isActive()))) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    var parties = new Parties(source, destination, designation);
    JobOrderBankPort port = commands.port();
    switch (attempt.getBankType()) {
      case null -> throw JobOrderLinkedMoneyAttempt.conflict();
      case AUTHORISATION ->
          requireCustodian(port, required(attempt.getBankCustodianId()), source.getUserId());
      case DEPOSIT -> deposit(port, attempt, parties);
      case WITHDRAWAL -> {
        if (attempt.getKind() != JobOrderMoneyKind.PROVISION || !attempt.isActualPayment()) {
          requireBankPayer(port, attempt, parties);
        }
        requireCounterparty(attempt, withdrawalPayee(attempt, parties));
      }
      case TRANSFER, HOLDER_TRANSFER -> {
        requireBankPayer(port, attempt, parties);
        requireTransferRecipient(port, attempt, parties);
      }
    }
  }

  private void requireCurrentAccountParties(UUID orderId, JobOrderLinkedMoneyAttempt attempt) {
    JobOrderBankSelection.Type bankType = attempt.getBankType();
    if (attempt.getKind() == JobOrderMoneyKind.PROVISION
        && (bankType == JobOrderBankSelection.Type.DEPOSIT
            || bankType == JobOrderBankSelection.Type.AUTHORISATION)) {
      return;
    }
    if (attempt.getKind() != JobOrderMoneyKind.PURPOSE_HANDOVER
        || bankType != JobOrderBankSelection.Type.TRANSFER
        || attempt.isActualPayment()) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    var designation = recipients.require(orderId, required(attempt.getRecipientId()));
    if (!designation.isActive() || designation.isSuperseded()) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    requireBankDesignation(designation, attempt.getBankTargetAccountId());
  }

  private void deposit(JobOrderBankPort port, JobOrderLinkedMoneyAttempt attempt, Parties parties) {
    var receivingHolder =
        attempt.getKind() == JobOrderMoneyKind.PROVISION
            ? parties.source()
            : required(parties.destination());
    requireCustodian(port, required(attempt.getBankCustodianId()), receivingHolder.getUserId());
    switch (attempt.getKind()) {
      case null -> throw JobOrderLinkedMoneyAttempt.conflict();
      case PROVISION -> {}
      case COLLECTION ->
          requireCounterparty(attempt, JobOrderLinkedMoneyCommands.party(parties.source()));
      case REQUESTER_RETURN ->
          requireCounterparty(
              attempt, counterparty(recipients.party(required(parties.designation()))));
      case HANDOVER,
          PURPOSE_HANDOVER,
          SETTLEMENT,
          RELEASE,
          SHORTAGE,
          PURPOSE_INFLOW,
          PURPOSE_OUTFLOW,
          CORRECTION ->
          throw JobOrderLinkedMoneyAttempt.conflict();
    }
  }

  private void requireBankPayer(
      JobOrderBankPort port, JobOrderLinkedMoneyAttempt attempt, Parties parties) {
    UUID user = parties.source().getUserId();
    if (attempt.getKind() == JobOrderMoneyKind.REQUESTER_RETURN) {
      var designation = required(parties.designation());
      UUID bankAccount = designation.getBankAccountId();
      if (bankAccount == null) {
        user = recipients.party(designation).accountId();
      } else {
        if (!bankAccount.equals(attempt.getBankAccountId())) {
          throw JobOrderLinkedMoneyAttempt.conflict();
        }
        if (attempt.personalHolderId().equals(attempt.getDestinationHolderId())
            && attempt.getSourceBinding().signum() == 0) {
          return;
        }
      }
    }
    requireCustodian(port, required(attempt.getBankCustodianId()), user);
  }

  private Counterparty withdrawalPayee(JobOrderLinkedMoneyAttempt attempt, Parties parties) {
    return switch (attempt.getKind()) {
      case PROVISION -> JobOrderLinkedMoneyCommands.party(parties.source());
      case COLLECTION, REQUESTER_RETURN ->
          JobOrderLinkedMoneyCommands.party(required(parties.destination()));
      case HANDOVER, PURPOSE_HANDOVER ->
          counterparty(recipients.party(required(parties.designation())));
      case SETTLEMENT -> selectionParty(attempt.selection());
      case RELEASE, SHORTAGE, PURPOSE_INFLOW, PURPOSE_OUTFLOW, CORRECTION ->
          throw JobOrderLinkedMoneyAttempt.conflict();
    };
  }

  private void requireTransferRecipient(
      JobOrderBankPort port, JobOrderLinkedMoneyAttempt attempt, Parties parties) {
    UUID custodian = required(attempt.getBankTargetCustodianId());
    switch (attempt.getKind()) {
      case null -> throw JobOrderLinkedMoneyAttempt.conflict();
      case HANDOVER, PURPOSE_HANDOVER ->
          requireBankDesignation(required(parties.designation()), attempt.getBankTargetAccountId());
      case COLLECTION, REQUESTER_RETURN ->
          requireCustodian(port, custodian, required(parties.destination()).getUserId());
      case SETTLEMENT ->
          requireCustodian(port, custodian, selectionParty(attempt.selection()).userId());
      case PROVISION, RELEASE, SHORTAGE, PURPOSE_INFLOW, PURPOSE_OUTFLOW, CORRECTION ->
          throw JobOrderLinkedMoneyAttempt.conflict();
    }
  }

  private static void requireBankDesignation(
      JobOrderMoneyRecipient designation, @Nullable UUID expectedAccount) {
    UUID current = designation.getBankAccountId();
    if (current == null || !current.equals(expectedAccount)) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
  }

  private JobOrderMoneyHolder canonicalHolder(UUID orderId, UUID holderId) {
    var holder =
        holders
            .findByOrderIdAndId(orderId, holderId)
            .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    UUID survivor = holder.getMergedIntoId();
    return survivor == null
        ? holder
        : holders
            .findByOrderIdAndId(orderId, survivor)
            .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
  }

  private static Counterparty selectionParty(JobOrderMoneySelection selection) {
    return switch (selection.kind()) {
      case LINKED -> new Counterparty(selection.reference(), null);
      case ACCOUNTLESS -> new Counterparty(null, selection.name());
      case EXISTING, SELF -> throw JobOrderLinkedMoneyAttempt.conflict();
    };
  }

  private static Counterparty counterparty(JobOrderMoneyRecipients.Party party) {
    return new Counterparty(party.accountId(), party.accountId() == null ? party.name() : null);
  }

  private static void requireCounterparty(
      JobOrderLinkedMoneyAttempt attempt, Counterparty current) {
    UUID user = current.userId();
    String name = StringNormalization.trimToNull(current.name());
    if ((user == null && (name == null || HandleAnonymisation.isReserved(name)))
        || !Objects.equals(new Counterparty(user, name), attempt.counterparty())) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
  }

  private static void requireCustodian(JobOrderBankPort port, UUID custodian, @Nullable UUID user) {
    try {
      port.requireCustodian(custodian, required(user));
    } catch (NotFoundException _) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
  }

  private static <T> T required(@Nullable T party) {
    if (party == null) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    return party;
  }

  /** Scoped order identities that explain the attempted payment. */
  private record Parties(
      JobOrderMoneyHolder source,
      @Nullable JobOrderMoneyHolder destination,
      @Nullable JobOrderMoneyRecipient designation) {}
}
