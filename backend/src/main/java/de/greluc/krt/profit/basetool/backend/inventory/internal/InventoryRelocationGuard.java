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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.StockRelocationLock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Holds every Lager write to existing rows against the relocation lock (REQ-PATCH-023): the rows
 * Hibernate updates or deletes in a writing transaction are noted as they flush, and before the
 * commit, once the transaction's last changes are flushed, a change to a row from before the open
 * home-location phase's live time is refused for anyone the lock refuses. A row a write only reads
 * is never refused, and a bulk statement, which flushes no entity, is not seen.
 */
@Component
@RequiredArgsConstructor
class InventoryRelocationGuard {

  /** The patch-change module's answer which rows are locked. */
  private final StockRelocationLock lock;

  /** The Lager rows, flushed before the check. */
  private final InventoryItemRepository rows;

  /** Arms the check of the current writing transaction once, when it loads a Lager row. */
  void watch() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || TransactionSynchronizationManager.hasResource(this)) {
      return;
    }
    Changes changes = new Changes();
    TransactionSynchronizationManager.bindResource(this, changes);
    TransactionSynchronizationManager.registerSynchronization(changes);
  }

  /**
   * Notes a row the current transaction updates or deletes.
   *
   * @param holder the row's holder
   * @param createdAt the row's creation time, or {@code null}
   */
  void changed(@NotNull UUID holder, @Nullable Instant createdAt) {
    if (TransactionSynchronizationManager.getResource(this) instanceof Changes changes) {
      changes.rows.add(new Change(holder, createdAt));
    }
  }

  /**
   * Refuses the transaction's changes when one of them touches a locked row.
   *
   * @param changes the changed rows
   * @throws RuntimeException the lock's {@code 409 RELOCATION_PENDING} problem
   */
  private void check(@NotNull Set<Change> changes) {
    if (changes.isEmpty()) {
      return;
    }
    Optional<Instant> liveAt = lock.openPhaseLiveAt();
    if (liveAt.isEmpty()) {
      return;
    }
    Set<UUID> holders = new HashSet<>();
    for (Change change : changes) {
      if (change.createdAt() == null || change.createdAt().isBefore(liveAt.get())) {
        holders.add(change.holder());
      }
    }
    if (!holders.isEmpty()) {
      lock.requireWritable(holders);
    }
  }

  /**
   * One changed row's holder and creation time.
   *
   * @param holder the holder
   * @param createdAt the creation time, or {@code null}
   */
  private record Change(@NotNull UUID holder, @Nullable Instant createdAt) {}

  /** The rows one transaction changed, checked before its commit after the guard's flush. */
  private final class Changes implements TransactionSynchronization {

    private final Set<Change> rows = new HashSet<>();

    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void beforeCommit(boolean readOnly) {
      if (!readOnly) {
        InventoryRelocationGuard.this.rows.flush();
        check(Set.copyOf(rows));
      }
    }

    @Override
    public void afterCompletion(int status) {
      TransactionSynchronizationManager.unbindResourceIfPossible(InventoryRelocationGuard.this);
    }
  }
}
