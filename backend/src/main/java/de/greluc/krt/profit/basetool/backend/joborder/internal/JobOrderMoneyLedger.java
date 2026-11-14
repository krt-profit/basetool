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

import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyAvailability.AccountReceipt;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Rebuilds the order's booked funds from original facts and their linked corrections. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyLedger {

  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyAccountHoldingRepository accountHoldings;
  private final JobOrderMoneyCorrectionEntryRepository corrections;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderLinkedMoneyAttemptRepository attempts;

  Ledger read(UUID orderId) {
    var facts = facts(orderId);
    Map<UUID, JobOrderMoneyHolder> byId =
        holders.findAllByOrderIdOrderById(orderId).stream()
            .collect(
                Collectors.toMap(
                    holder -> Objects.requireNonNull(holder.getId()), Function.identity()));
    Map<UUID, JobOrderMoneyAccountHolding> byAccountHolding =
        accountHoldings.findAllByOrderIdOrderById(orderId).stream()
            .collect(
                Collectors.toMap(
                    account -> Objects.requireNonNull(account.getId()), Function.identity()));
    var entries = corrections.findAllByOrderIdOrderByIdAsc(orderId);
    var recipientAliases = recipients.correctedRecipientAliases(orderId);
    var activeAttempts = attempts.findAllByOrderIdAndActiveTrue(orderId);
    var availability =
        JobOrderMoneyAvailability.collect(
            activeAttempts,
            id -> Ledger.canonical(byId, id),
            id -> recipientAliases.getOrDefault(id, id));
    return Ledger.fold(
        facts,
        byId,
        byAccountHolding,
        recipientAliases,
        entries,
        revisedAmounts(Map.of(), entries),
        availability,
        activeAttempts.stream()
            .collect(
                Collectors.toMap(JobOrderLinkedMoneyAttempt::getOperationId, Function.identity())));
  }

  void requireIndependentOperation(UUID orderId, UUID operationId) {
    if (attempts.existsByOrderIdAndOperationIdAndActiveTrue(orderId, operationId)) {
      throw new BusinessConflictException("orders.money.operation.termsMismatch");
    }
  }

  List<JobOrderMoneyOperation> facts(UUID orderId) {
    return operations.findAllByOrderIdOrderByRecordedAtAscIdAsc(orderId);
  }

  BigDecimal delivered(UUID orderId) {
    return sumHandover(
        facts(orderId),
        revisedAmounts(Map.of(), corrections.findAllByOrderIdOrderByIdAsc(orderId)));
  }

  BigDecimal deliveredAfter(UUID orderId, Map<UUID, BigDecimal> correctedAmounts) {
    var revisions = revisedAmounts(Map.of(), corrections.findAllByOrderIdOrderByIdAsc(orderId));
    correctedAmounts.forEach((id, amount) ->
        revisions.put(id, new JobOrderMoneyAmounts(amount, BigDecimal.ZERO, BigDecimal.ZERO)));
    return sumHandover(facts(orderId), revisions);
  }

  private static BigDecimal sumHandover(
      List<JobOrderMoneyOperation> facts, Map<UUID, JobOrderMoneyAmounts> revisions) {
    return facts.stream()
        .map(fact -> fact.handoverEffect(amounts(fact, revisions)))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static JobOrderMoneyAmounts amounts(
      JobOrderMoneyOperation fact, Map<UUID, JobOrderMoneyAmounts> revisions) {
    JobOrderMoneyAmounts revised = revisions.get(fact.getOperationId());
    return revised == null ? fact.amounts() : revised;
  }

  private static Map<UUID, JobOrderMoneyAmounts> revisedAmounts(
      Map<UUID, JobOrderMoneyAmounts> previous, List<JobOrderMoneyCorrectionEntry> entries) {
    var revisions = new HashMap<>(previous);
    entries.forEach(entry -> revisions.put(entry.getOriginalOperationId(), entry.revised()));
    return revisions;
  }

  /**
   * One order's effective financial figures and their reconstructible basis.
   *
   * @param facts accepted original and correction operations
   * @param identities original holders and retained aliases
   * @param accountHoldings order-local positions by Bank account
   * @param recipientAliases erroneous designations and their current replacements
   * @param corrections linked effects in committed order
   * @param revisions latest effect for each corrected original fact
   * @param balances effective balances by canonical holder
   * @param accountBalances effective balances by order-local Bank account position
   * @param requesterBalances effective net handover by requester designation
   * @param personalRequesterBalances effective net handover from personal holders by designation
   * @param accountRequesterBalances effective net handover by collection position and designation
   * @param provided actual new funds recorded for this order
   * @param handedOver effective net handover to requester recipients
   * @param fees fees paid by this order
   * @param purposeInflow money reassigned from another order
   * @param purposeOutflow money reassigned to another order
   * @param released money released from the order's purpose
   * @param shortages real later losses
   * @param settled principal paid to a third-party beneficiary
   * @param availability funds bound by active linked attempts
   * @param linkedAttempts the current linked revision for each operation
   */
  record Ledger(
      List<JobOrderMoneyOperation> facts,
      Map<UUID, JobOrderMoneyHolder> identities,
      Map<UUID, JobOrderMoneyAccountHolding> accountHoldings,
      Map<UUID, UUID> recipientAliases,
      List<JobOrderMoneyCorrectionEntry> corrections,
      Map<UUID, JobOrderMoneyAmounts> revisions,
      Map<UUID, BigDecimal> balances,
      Map<UUID, BigDecimal> accountBalances,
      Map<UUID, BigDecimal> requesterBalances,
      Map<UUID, BigDecimal> personalRequesterBalances,
      Map<AccountReceipt, BigDecimal> accountRequesterBalances,
      BigDecimal provided,
      BigDecimal handedOver,
      BigDecimal fees,
      BigDecimal purposeInflow,
      BigDecimal purposeOutflow,
      BigDecimal released,
      BigDecimal shortages,
      BigDecimal settled,
      JobOrderMoneyAvailability availability,
      Map<UUID, JobOrderLinkedMoneyAttempt> linkedAttempts) {

    static Ledger fold(
        List<JobOrderMoneyOperation> facts,
        Map<UUID, JobOrderMoneyHolder> identities,
        Map<UUID, JobOrderMoneyAccountHolding> accountHoldings,
        Map<UUID, UUID> recipientAliases,
        List<JobOrderMoneyCorrectionEntry> corrections,
        Map<UUID, JobOrderMoneyAmounts> revisions,
        JobOrderMoneyAvailability availability,
        Map<UUID, JobOrderLinkedMoneyAttempt> linkedAttempts) {
      Map<UUID, BigDecimal> balances = new HashMap<>();
      Map<UUID, BigDecimal> accountBalances = new HashMap<>();
      Map<UUID, BigDecimal> requesterBalances = new HashMap<>();
      Map<UUID, BigDecimal> personalRequesterBalances = new HashMap<>();
      Map<AccountReceipt, BigDecimal> accountRequesterBalances = new HashMap<>();
      BigDecimal provision = BigDecimal.ZERO;
      BigDecimal fees = BigDecimal.ZERO;
      var movements = new EnumMap<JobOrderMoneyKind, BigDecimal>(JobOrderMoneyKind.class);
      for (JobOrderMoneyOperation fact : facts) {
        JobOrderMoneyAmounts effective = amounts(fact, revisions);
        provision = provision.add(effective.provision());
        fees = fees.add(effective.fee());
        UUID accountHoldingId = fact.getAccountHoldingId();
        if (accountHoldingId == null) {
          add(balances, identities, fact.personalHolderId(), fact.holderEffect(effective));
        } else {
          BigDecimal accountEffect =
              fact.getKind() == JobOrderMoneyKind.REQUESTER_RETURN
                  ? effective.amount()
                  : fact.holderEffect(effective);
          accountBalances.merge(accountHoldingId, accountEffect, BigDecimal::add);
        }
        if (fact.getDestinationHolderId() != null) {
          add(balances, identities, fact.getDestinationHolderId(), effective.amount());
        }
        UUID recipientId = fact.getRecipientId();
        if (recipientId != null) {
          UUID currentRecipient = recipientAliases.getOrDefault(recipientId, recipientId);
          BigDecimal delivered = fact.handoverEffect(effective);
          requesterBalances.merge(currentRecipient, delivered, BigDecimal::add);
          if (accountHoldingId == null) {
            personalRequesterBalances.merge(currentRecipient, delivered, BigDecimal::add);
          } else {
            accountRequesterBalances.merge(
                new AccountReceipt(accountHoldingId, currentRecipient), delivered, BigDecimal::add);
          }
        }
        movements.merge(fact.getKind(), effective.amount(), BigDecimal::add);
      }
      return new Ledger(
          facts,
          identities,
          accountHoldings,
          recipientAliases,
          corrections,
          revisions,
          balances,
          accountBalances,
          requesterBalances,
          personalRequesterBalances,
          accountRequesterBalances,
          provision,
          requesterBalances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
          fees,
          movements.getOrDefault(JobOrderMoneyKind.PURPOSE_INFLOW, BigDecimal.ZERO),
          movements.getOrDefault(JobOrderMoneyKind.PURPOSE_OUTFLOW, BigDecimal.ZERO),
          movements.getOrDefault(JobOrderMoneyKind.RELEASE, BigDecimal.ZERO),
          movements.getOrDefault(JobOrderMoneyKind.SHORTAGE, BigDecimal.ZERO),
          movements.getOrDefault(JobOrderMoneyKind.SETTLEMENT, BigDecimal.ZERO),
          availability,
          linkedAttempts);
    }

    Ledger revised(List<JobOrderMoneyCorrectionEntry> entries) {
      return fold(
          facts,
          identities,
          accountHoldings,
          recipientAliases,
          corrections,
          revisedAmounts(revisions, entries),
          availability,
          linkedAttempts);
    }

    /**
     * Folds only the facts recorded before the cutoff, at their current effective amounts and
     * holder identities: the order's funds as they stood then, as corrected since (REQ-PATCH-044).
     *
     * @param cutoff the exclusive upper bound of the recording time
     * @return the earlier ledger of the same order
     */
    Ledger before(Instant cutoff) {
      return fold(
          facts.stream().filter(fact -> fact.getRecordedAt().isBefore(cutoff)).toList(),
          identities,
          accountHoldings,
          recipientAliases,
          corrections,
          revisions,
          availability,
          linkedAttempts);
    }

    JobOrderMoneyAmounts effective(JobOrderMoneyOperation fact) {
      return amounts(fact, revisions);
    }

    boolean nonnegativeAvailable() {
      return balances.values().stream().allMatch(amount -> amount.signum() >= 0)
          && accountBalances.values().stream().allMatch(amount -> amount.signum() >= 0)
          && requesterBalances.values().stream().allMatch(amount -> amount.signum() >= 0)
          && personalRequesterBalances.values().stream().allMatch(amount -> amount.signum() >= 0)
          && accountRequesterBalances.values().stream().allMatch(amount -> amount.signum() >= 0)
          && availability.coveredBy(
              balances, accountBalances, personalRequesterBalances, accountRequesterBalances);
    }

    boolean canCorrect(JobOrderMoneyOperation fact) {
      if (!fact.isBankHandling()) {
        return true;
      }
      JobOrderLinkedMoneyAttempt attempt = linkedAttempts.get(fact.getOperationId());
      return attempt != null
          && (attempt.getState() == JobOrderLinkedMoneyState.AUTHORISED
              || attempt.getState() == JobOrderLinkedMoneyState.CLARIFICATION);
    }

    @Nullable
    BigDecimal historicalOrderFeeRate(JobOrderMoneyOperation fact) {
      if (!fact.isBankHandling()) {
        return fact.getFeeRate();
      }
      JobOrderLinkedMoneyAttempt attempt = linkedAttempts.get(fact.getOperationId());
      return attempt != null && attempt.getFeeFunding() == JobOrderMoneyFeeFunding.ORDER
          ? attempt.getRate()
          : null;
    }

    BigDecimal availableForPersonalReturn(UUID recipientId, @Nullable UUID excludedAttemptId) {
      UUID recipient = recipientAliases.getOrDefault(recipientId, recipientId);
      return personalRequesterBalances
          .getOrDefault(recipient, BigDecimal.ZERO)
          .subtract(availability.forPersonalReceipt(recipient, excludedAttemptId))
          .min(availableForDesignation(recipient, excludedAttemptId));
    }

    private BigDecimal availableForDesignation(UUID recipient, @Nullable UUID excludedAttemptId) {
      return requesterBalances
          .getOrDefault(recipient, BigDecimal.ZERO)
          .subtract(availability.forRecipient(recipient, excludedAttemptId));
    }

    BigDecimal availableForAccountReturn(
        UUID holdingId, UUID recipientId, @Nullable UUID excludedAttemptId) {
      UUID recipient = recipientAliases.getOrDefault(recipientId, recipientId);
      var receipt = new AccountReceipt(holdingId, recipient);
      return accountRequesterBalances
          .getOrDefault(receipt, BigDecimal.ZERO)
          .subtract(availability.forAccountReceipt(receipt, excludedAttemptId))
          .min(availableForDesignation(recipient, excludedAttemptId));
    }

    BigDecimal balance(@Nullable UUID holderId) {
      return holderId == null
          ? BigDecimal.ZERO
          : balances.getOrDefault(canonical(identities, holderId), BigDecimal.ZERO);
    }

    BigDecimal availableBalance(@Nullable UUID holderId) {
      return availableBalance(holderId, null);
    }

    BigDecimal availableBalance(@Nullable UUID holderId, @Nullable UUID excludedAttemptId) {
      if (holderId == null) {
        return BigDecimal.ZERO;
      }
      UUID canonical = canonical(identities, holderId);
      return balances
          .getOrDefault(canonical, BigDecimal.ZERO)
          .subtract(availability.forHolder(canonical, excludedAttemptId));
    }

    BigDecimal accountBalance(UUID holdingId) {
      return accountBalances.getOrDefault(holdingId, BigDecimal.ZERO);
    }

    BigDecimal availableAccountBalance(UUID holdingId) {
      return availableAccountBalance(holdingId, null);
    }

    BigDecimal availableAccountBalance(UUID holdingId, @Nullable UUID excludedAttemptId) {
      return accountBalance(holdingId)
          .subtract(availability.forAccount(holdingId, excludedAttemptId));
    }

    BigDecimal holdings() {
      return Stream.concat(balances.values().stream(), accountBalances.values().stream())
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    JobOrderMoneyHolder holder(UUID id) {
      return Objects.requireNonNull(identities.get(canonical(identities, id)));
    }

    private static void add(
        Map<UUID, BigDecimal> balances,
        Map<UUID, JobOrderMoneyHolder> identities,
        UUID id,
        BigDecimal delta) {
      balances.merge(canonical(identities, id), delta, BigDecimal::add);
    }

    private static UUID canonical(Map<UUID, JobOrderMoneyHolder> identities, UUID id) {
      UUID alias = Objects.requireNonNull(identities.get(id)).getMergedIntoId();
      return alias == null ? id : alias;
    }
  }
}
