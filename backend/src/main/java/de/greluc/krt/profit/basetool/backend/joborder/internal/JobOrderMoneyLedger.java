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

/** Rebuilds the order's booked funds from its effective monetary facts. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyLedger {

  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyHolderRepository holders;

  Ledger read(UUID orderId) {
    List<JobOrderMoneyOperation> facts =
        operations.findAllByOrderIdOrderByRecordedAtAscIdAsc(orderId);
    Map<UUID, JobOrderMoneyHolder> byId =
        holders.findAllByOrderIdOrderById(orderId).stream()
            .collect(
                Collectors.toMap(
                    holder -> Objects.requireNonNull(holder.getId()), Function.identity()));
    return Ledger.fold(facts, byId);
  }

  BigDecimal delivered(UUID orderId) {
    return delivered(operations.findAllByOrderIdOrderByRecordedAtAscIdAsc(orderId));
  }

  private static BigDecimal delivered(List<JobOrderMoneyOperation> facts) {
    return facts.stream()
        .filter(fact -> fact.getKind().delivers())
        .map(JobOrderMoneyOperation::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * A consistent fold of the accepted facts and current holder identities.
   *
   * @param facts accepted money operations
   * @param identities original holders and retained aliases
   * @param balances effective balances by canonical holder
   * @param provided actual new funds recorded for this order
   * @param handedOver net delivered to requester recipients
   * @param fees fees paid by this order
   */
  record Ledger(
      List<JobOrderMoneyOperation> facts,
      Map<UUID, JobOrderMoneyHolder> identities,
      Map<UUID, BigDecimal> balances,
      BigDecimal provided,
      BigDecimal handedOver,
      BigDecimal fees) {

    static Ledger fold(
        List<JobOrderMoneyOperation> facts, Map<UUID, JobOrderMoneyHolder> identities) {
      Map<UUID, BigDecimal> balances = new HashMap<>();
      BigDecimal provision = BigDecimal.ZERO;
      BigDecimal fees = BigDecimal.ZERO;
      for (JobOrderMoneyOperation fact : facts) {
        provision = provision.add(fact.providedAmount());
        fees = fees.add(fact.feeAmount());
        add(balances, identities, fact.getHolderId(), fact.sourceEffect());
        if (fact.getDestinationHolderId() != null) {
          add(balances, identities, fact.getDestinationHolderId(), fact.getAmount());
        }
      }
      return new Ledger(facts, identities, balances, provision, delivered(facts), fees);
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
