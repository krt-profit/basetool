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

package de.greluc.krt.profit.basetool.backend.kernel;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hibernate.engine.spi.SessionImplementor;
import org.jetbrains.annotations.NotNull;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shares transaction-bound memo state and persistence-context traversal. */
public final class TransactionMemo {

  private TransactionMemo() {}

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
  public static <T> @NotNull T bound(
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
          public void suspend() {
            TransactionSynchronizationManager.unbindResource(owner);
          }

          @Override
          public void resume() {
            TransactionSynchronizationManager.bindResource(owner, memo);
          }

          @Override
          public void afterCompletion(int status) {
            TransactionSynchronizationManager.unbindResourceIfPossible(owner);
          }
        });
    return memo;
  }

  /**
   * Returns the identifiers referenced by managed entities of one type.
   *
   * @param entityManager the shared entity manager
   * @param type the managed entity type
   * @param targetId extracts the referenced identifier, or {@code null} for no reference
   * @param <T> the managed entity type
   * @return the distinct non-null identifiers
   */
  public static <T> @NotNull Set<UUID> idsInContext(
      @NotNull EntityManager entityManager,
      @NotNull Class<T> type,
      @NotNull Function<T, UUID> targetId) {
    Set<UUID> ids = new LinkedHashSet<>();
    forEachEntity(
        entityManager,
        type,
        entity -> {
          UUID id = targetId.apply(entity);
          if (id != null) {
            ids.add(id);
          }
        });
    return ids;
  }

  /**
   * Returns the managed entities of one type in the active transaction.
   *
   * @param entityManager the shared entity manager
   * @param type the managed entity type
   * @param <T> the managed entity type
   * @return the managed entities, empty without an active transaction
   */
  public static <T> @NotNull List<T> entitiesInContext(
      @NotNull EntityManager entityManager, @NotNull Class<T> type) {
    List<T> entities = new ArrayList<>();
    forEachEntity(entityManager, type, entities::add);
    return entities;
  }

  private static <T> void forEachEntity(
      EntityManager entityManager, Class<T> type, Consumer<T> visit) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      return;
    }
    for (Map.Entry<Object, ?> entry :
        entityManager
            .unwrap(SessionImplementor.class)
            .getPersistenceContextInternal()
            .reentrantSafeEntityEntries()) {
      if (type.isInstance(entry.getKey())) {
        visit.accept(type.cast(entry.getKey()));
      }
    }
  }
}
