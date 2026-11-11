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

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Rebuilds the order's booked funds from original facts and their linked corrections. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyLedger {

  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyCorrectionEntryRepository corrections;
  private final JobOrderMoneyRecipients recipients;

  Ledger read(UUID orderId) {
    var facts = facts(orderId);
    Map<UUID, JobOrderMoneyHolder> byId =
        holders.findAllByOrderIdOrderById(orderId).stream()
            .collect(
                Collectors.toMap(
                    holder -> Objects.requireNonNull(holder.getId()), Function.identity()));
    var entries = corrections.findAllByOrderIdOrderByIdAsc(orderId);
    return Ledger.fold(
        facts,
        byId,
        recipients.correctedRecipientAliases(orderId),
        entries,
        revisedAmounts(Map.of(), entries));
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
   * @param recipientAliases erroneous designations and their current replacements
   * @param corrections linked effects in committed order
   * @param revisions latest effect for each corrected original fact
   * @param balances effective balances by canonical holder
   * @param requesterBalances effective net handover by requester designation
   * @param provided actual new funds recorded for this order
   * @param handedOver effective net handover to requester recipients
   * @param fees fees paid by this order
   * @param purposeInflow money reassigned from another order
   * @param purposeOutflow money reassigned to another order
   * @param released money released from the order's purpose
   * @param shortages real later losses
   * @param settled principal paid to a third-party beneficiary
   */
  record Ledger(
      List<JobOrderMoneyOperation> facts,
      Map<UUID, JobOrderMoneyHolder> identities,
      Map<UUID, UUID> recipientAliases,
      List<JobOrderMoneyCorrectionEntry> corrections,
      Map<UUID, JobOrderMoneyAmounts> revisions,
      Map<UUID, BigDecimal> balances,
      Map<UUID, BigDecimal> requesterBalances,
      BigDecimal provided,
      BigDecimal handedOver,
      BigDecimal fees,
      BigDecimal purposeInflow,
      BigDecimal purposeOutflow,
      BigDecimal released,
      BigDecimal shortages,
      BigDecimal settled) {

    static Ledger fold(
        List<JobOrderMoneyOperation> facts,
        Map<UUID, JobOrderMoneyHolder> identities,
        Map<UUID, UUID> recipientAliases,
        List<JobOrderMoneyCorrectionEntry> corrections,
        Map<UUID, JobOrderMoneyAmounts> revisions) {
      Map<UUID, BigDecimal> balances = new HashMap<>();
      Map<UUID, BigDecimal> requesterBalances = new HashMap<>();
      BigDecimal provision = BigDecimal.ZERO;
      BigDecimal fees = BigDecimal.ZERO;
      var movements = new EnumMap<JobOrderMoneyKind, BigDecimal>(JobOrderMoneyKind.class);
      for (JobOrderMoneyOperation fact : facts) {
        JobOrderMoneyAmounts effective = amounts(fact, revisions);
        provision = provision.add(effective.provision());
        fees = fees.add(effective.fee());
        add(balances, identities, fact.getHolderId(), fact.holderEffect(effective));
        if (fact.getDestinationHolderId() != null) {
          add(balances, identities, fact.getDestinationHolderId(), effective.amount());
        }
        UUID recipientId = fact.getRecipientId();
        if (recipientId != null) {
          UUID currentRecipient = recipientAliases.getOrDefault(recipientId, recipientId);
          requesterBalances.merge(
              currentRecipient, fact.handoverEffect(effective), BigDecimal::add);
        }
        movements.merge(fact.getKind(), effective.amount(), BigDecimal::add);
      }
      return new Ledger(
          facts,
          identities,
          recipientAliases,
          corrections,
          revisions,
          balances,
          requesterBalances,
          provision,
          requesterBalances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
          fees,
          movements.getOrDefault(JobOrderMoneyKind.PURPOSE_INFLOW, BigDecimal.ZERO),
          movements.getOrDefault(JobOrderMoneyKind.PURPOSE_OUTFLOW, BigDecimal.ZERO),
          movements.getOrDefault(JobOrderMoneyKind.RELEASE, BigDecimal.ZERO),
          movements.getOrDefault(JobOrderMoneyKind.SHORTAGE, BigDecimal.ZERO),
          movements.getOrDefault(JobOrderMoneyKind.SETTLEMENT, BigDecimal.ZERO));
    }

    Ledger revised(List<JobOrderMoneyCorrectionEntry> entries) {
      return fold(
          facts, identities, recipientAliases, corrections, revisedAmounts(revisions, entries));
    }

    JobOrderMoneyAmounts effective(JobOrderMoneyOperation fact) {
      return amounts(fact, revisions);
    }

    boolean nonnegative() {
      return balances.values().stream().allMatch(amount -> amount.signum() >= 0)
          && requesterBalances.values().stream().allMatch(amount -> amount.signum() >= 0);
    }

    BigDecimal netForRecipient(UUID recipientId) {
      return requesterBalances.getOrDefault(recipientId, BigDecimal.ZERO);
    }

    BigDecimal balance(@Nullable UUID holderId) {
      return holderId == null
          ? BigDecimal.ZERO
          : balances.getOrDefault(canonical(identities, holderId), BigDecimal.ZERO);
    }

    BigDecimal holdings() {
      return balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
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
