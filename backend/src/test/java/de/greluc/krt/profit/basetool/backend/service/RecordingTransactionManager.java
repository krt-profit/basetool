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

package de.greluc.krt.profit.basetool.backend.service;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A transaction manager for Mockito unit tests of the UEX syncs: it opens no resource and only
 * counts how many transactions began, committed and rolled back, so a test can wire a real {@link
 * SyncChunkWriter} (whose callbacks then actually run against the mocked repositories) and still
 * assert the transaction shape — one per chunk, a rolled-back chunk retried row by row.
 */
class RecordingTransactionManager extends AbstractPlatformTransactionManager {

  /** Transactions begun. */
  int begun;

  /** Transactions committed. */
  int committed;

  /** Transactions rolled back. */
  int rolledBack;

  @Override
  protected Object doGetTransaction() {
    return new Object();
  }

  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    begun++;
  }

  @Override
  protected void doCommit(DefaultTransactionStatus status) {
    committed++;
  }

  @Override
  protected void doRollback(DefaultTransactionStatus status) {
    rolledBack++;
  }

  @Override
  protected Object doSuspend(Object transaction) {
    return transaction;
  }

  @Override
  protected void doResume(Object transaction, Object suspendedResources) {
    // Nothing is bound, so there is nothing to restore.
  }
}
