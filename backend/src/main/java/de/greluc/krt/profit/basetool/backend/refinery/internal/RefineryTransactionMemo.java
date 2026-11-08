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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.hibernate.engine.spi.SessionImplementor;
import org.jetbrains.annotations.NotNull;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The per-transaction memo the refinery's read lookups share, so a page of orders costs one lookup
 * per kind of reference, not one per row (REQ-DATA-003).
 */
final class RefineryTransactionMemo {

  /** Not instantiable. */
  private RefineryTransactionMemo() {}

  /**
   * Returns the memo bound to {@code owner} in the running transaction, binding a new one on first
   * use and unbinding it when the transaction completes; outside a transaction every call gets a
   * fresh memo.
   *
   * @param owner the lookup that owns the memo, the binding key
   * @param type the memo's class
   * @param factory makes an empty memo
   * @param <T> the memo type
   * @return the bound memo, or a fresh one outside a transaction
   */
  static <T> @NotNull T bound(
      @NotNull Object owner, @NotNull Class<T> type, @NotNull Supplier<T> factory) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return factory.get();
    }
    Object resource = TransactionSynchronizationManager.getResource(owner);
    if (type.isInstance(resource)) {
      return type.cast(resource);
    }
    T memo = factory.get();
    TransactionSynchronizationManager.bindResource(owner, memo);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(owner);
          }
        });
    return memo;
  }

  /**
   * Returns the refinery orders the running transaction's persistence context holds; empty outside
   * a transaction.
   *
   * @param entityManager the shared entity manager
   * @return the managed orders
   */
  static @NotNull List<RefineryOrder> ordersInContext(@NotNull EntityManager entityManager) {
    List<RefineryOrder> orders = new ArrayList<>();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      return orders;
    }
    for (Map.Entry<Object, ?> entry :
        entityManager
            .unwrap(SessionImplementor.class)
            .getPersistenceContextInternal()
            .reentrantSafeEntityEntries()) {
      if (entry.getKey() instanceof RefineryOrder order) {
        orders.add(order);
      }
    }
    return orders;
  }
}
