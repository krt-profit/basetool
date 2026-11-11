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
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** Retains only local bindings against already booked holdings and requester net balances. */
@RequiredArgsConstructor
final class JobOrderMoneyAvailability {

  private final Map<UUID, BigDecimal> holders;
  private final Map<UUID, BigDecimal> accounts;
  private final Map<UUID, BigDecimal> recipients;
  private final Map<AccountReceipt, BigDecimal> accountReceipts;
  private final Map<UUID, Binding> byAttempt;

  static JobOrderMoneyAvailability collect(
      List<JobOrderLinkedMoneyAttempt> attempts,
      Function<UUID, UUID> canonicalHolder,
      Function<UUID, UUID> canonicalRecipient) {
    Map<UUID, BigDecimal> held = new HashMap<>();
    Map<UUID, BigDecimal> onAccounts = new HashMap<>();
    Map<UUID, BigDecimal> delivered = new HashMap<>();
    Map<AccountReceipt, BigDecimal> accountDelivered = new HashMap<>();
    Map<UUID, Binding> byAttempt = new HashMap<>();
    for (JobOrderLinkedMoneyAttempt attempt : attempts) {
      if (!attempt.bindingActive()) {
        continue;
      }
      UUID account = attempt.getAccountHoldingId();
      UUID holder = account == null ? canonicalHolder.apply(attempt.personalHolderId()) : null;
      BigDecimal source = attempt.getSourceBinding();
      if (account == null) {
        held.merge(Objects.requireNonNull(holder), source, BigDecimal::add);
      } else {
        onAccounts.merge(account, source, BigDecimal::add);
      }
      UUID recipient = null;
      BigDecimal principal = BigDecimal.ZERO;
      if (attempt.getKind() == JobOrderMoneyKind.REQUESTER_RETURN) {
        recipient = canonicalRecipient.apply(Objects.requireNonNull(attempt.getRecipientId()));
        principal = attempt.getAmount();
        delivered.merge(recipient, principal, BigDecimal::add);
        if (account != null) {
          accountDelivered.merge(
              new AccountReceipt(account, recipient), principal, BigDecimal::add);
        }
      }
      byAttempt.put(attempt.getId(), new Binding(holder, account, source, recipient, principal));
    }
    return new JobOrderMoneyAvailability(held, onAccounts, delivered, accountDelivered, byAttempt);
  }

  BigDecimal forHolder(UUID holderId, @Nullable UUID excludedAttemptId) {
    BigDecimal bound = holders.getOrDefault(holderId, BigDecimal.ZERO);
    Binding excluded = byAttempt.get(excludedAttemptId);
    return excluded != null && holderId.equals(excluded.holderId())
        ? bound.subtract(excluded.source())
        : bound;
  }

  BigDecimal forAccount(UUID accountHoldingId, @Nullable UUID excludedAttemptId) {
    BigDecimal bound = accounts.getOrDefault(accountHoldingId, BigDecimal.ZERO);
    Binding excluded = byAttempt.get(excludedAttemptId);
    return excluded != null && accountHoldingId.equals(excluded.accountHoldingId())
        ? bound.subtract(excluded.source())
        : bound;
  }

  BigDecimal forRecipient(UUID recipientId, @Nullable UUID excludedAttemptId) {
    BigDecimal bound = recipients.getOrDefault(recipientId, BigDecimal.ZERO);
    Binding excluded = byAttempt.get(excludedAttemptId);
    return excluded != null && recipientId.equals(excluded.recipientId())
        ? bound.subtract(excluded.principal())
        : bound;
  }

  BigDecimal forAccountReceipt(AccountReceipt receipt, @Nullable UUID excludedAttemptId) {
    BigDecimal bound = accountReceipts.getOrDefault(receipt, BigDecimal.ZERO);
    Binding excluded = byAttempt.get(excludedAttemptId);
    return excluded != null
            && receipt.holdingId().equals(excluded.accountHoldingId())
            && receipt.recipientId().equals(excluded.recipientId())
        ? bound.subtract(excluded.principal())
        : bound;
  }

  boolean coveredBy(
      Map<UUID, BigDecimal> balances,
      Map<UUID, BigDecimal> accountBalances,
      Map<UUID, BigDecimal> requesterBalances,
      Map<AccountReceipt, BigDecimal> accountRequesterBalances) {
    return covered(holders, balances)
        && covered(accounts, accountBalances)
        && covered(recipients, requesterBalances)
        && covered(accountReceipts, accountRequesterBalances);
  }

  private static <K> boolean covered(Map<K, BigDecimal> bindings, Map<K, BigDecimal> balances) {
    return bindings.entrySet().stream()
        .allMatch(entry ->
            balances.getOrDefault(entry.getKey(), BigDecimal.ZERO).compareTo(entry.getValue())
                >= 0);
  }

  /** Identifies the order collection position that funded a particular requester receipt. */
  record AccountReceipt(UUID holdingId, UUID recipientId) {}

  /**
   * The two separately spendable sources that this attempt can release while applying its effect.
   */
  private record Binding(
      @Nullable UUID holderId,
      @Nullable UUID accountHoldingId,
      BigDecimal source,
      @Nullable UUID recipientId,
      BigDecimal principal) {}
}
